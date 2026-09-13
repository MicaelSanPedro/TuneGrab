#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TuneGrab passgen — gerador das senhas de convite (v0.20.0). Só roda FORA do
repo público com o pepper secreto em mãos (mesma ferramenta que o autor usa
com a IA: scripts/tunegrab_passgen.py na máquina do autor).

Cadeia de derivação (pedido do autor: senhas geradas com base no nome
completo da pessoa, "criptografado em base64"):

    nome completo
      -> NFKD, sem acentos, espaços colapsados, MAIÚSCULAS   (normalização)
      -> base64(nome normalizado)                            (o "base64" do autor)
      -> HMAC-SHA256(PEPPER secreto, base64)                 (a chave é o segredo)
      -> 8 chars do alfabeto sem 0/O/1/I/L                   (fácil de ditar)
      -> display "TUNE-XXXX-XXXX"  ·  canônico "TUNEXXXXXXXX"
      -> PBKDF2-HMAC-SHA256(canônico, salt 16B, iters, 32B)
      -> só o HASH entra em access/passwords.json

Mesmo nome = mesma senha (determinístico): com o pepper certo, --show
recupera a senha de qualquer pessoa a qualquer momento.

v0.21.0 — TRAVA POR APARELHO (pedido do autor: "uma senha e ela só funciona
naquele dispositivo, se tentar em outro, não pega"):

    --device XXXX-XXXX  (o "Código deste aparelho" que o app mostra na tela
    de senha, derivado do ANDROID_ID — veja AccessGate.deviceCodeFrom)

      -> canônico do código: 8 chars MAIÚSCULOS sem traço (AB2C9XYZ)
      -> HMAC-SHA256(salt 16B da entrada, canônico) -> campo "device" do JSON
      -> o app repete o MESMO HMAC com o código local e compara tempo-
         constante; não bateu = "essa senha pertence a outro aparelho"

    Sem --device a entrada NASCE SOLTA (vale em qualquer aparelho). O
    binding NÃO usa o pepper — o código do aparelho não é segredo, é só o
    par (senha, aparelho) que o app confere.

PEPPER NUNCA é commitado nem gravado por este script — passe com --pepper
(hex) ou na variável de ambiente TUNE_PEPPER. Perdeu o pepper? Todas as
senhas derivadas morrem juntas: gera outro e refaz a lista de hashes.

Uso:
  python3 tools/passgen.py --selftest
  python3 tools/passgen.py --name "Maria Silva" [--name "..."]
  python3 tools/passgen.py --name "Maria Silva" --device AB2C-9XYZ
  python3 tools/passgen.py --show --name "Maria Silva"
  python3 tools/passgen.py --remove maria-silva
  python3 tools/passgen.py --json caminho/passwords.json --pepper <hex> ...
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import struct
import sys
import unicodedata
from datetime import datetime, timezone

ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"  # 31 chars — sem 0, O, 1, I, L
DEFAULT_ITERS = 100000
# Espelho EXATO do DEVICE_CODE_KEY do AccessGate.kt (congelado pra sempre:
# trocou = todos os códigos de aparelho mudam). Não é segredo — está no APK.
DEVICE_KEY = b"TuneGrabDeviceCode-v1"
DEFAULT_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            os.pardir, "access", "passwords.json")


def normalize_name(raw):
    n = unicodedata.normalize("NFKD", raw)
    n = "".join(c for c in n if not unicodedata.combining(c))
    n = re.sub(r"\s+", " ", n).strip().upper()
    if not n:
        raise ValueError("nome vazio")
    return n


def slugify(norm):
    s = re.sub(r"[^a-z0-9-]", "", norm.lower().replace(" ", "-"))
    return s or "sem-nome"


def derive_password(norm, pepper):
    """nome normalizado + pepper -> (display, canonical)."""
    b64 = base64.b64encode(norm.encode("utf-8")).decode("ascii")
    digest = hmac.new(pepper, b64.encode("ascii"), hashlib.sha256).digest()
    bits = int.from_bytes(digest[:5], "big")
    chars = "".join(ALPHABET[((bits >> (5 * (7 - i))) & 0x1F) % 31] for i in range(8))
    canonical = "TUNE" + chars
    display = "TUNE-{}-{}".format(chars[:4], chars[4:])
    return display, canonical


def hash_password(canonical, iters):
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", canonical.encode("utf-8"), salt, iters, dklen=32)
    return salt, dk


def device_code_from(raw):
    """Espelho EXATO do AccessGate.deviceCodeFrom (Kotlin): ID bruto -> XXXX-XXXX.
    Mesmo HMAC, mesmos 5 bytes, mesmo passeio de 5 bits no alfabeto de 31."""
    canonical = raw.strip().lower()
    digest = hmac.new(DEVICE_KEY, canonical.encode("utf-8"), hashlib.sha256).digest()
    bits = int.from_bytes(digest[:5], "big")
    chars = "".join(ALPHABET[((bits >> (5 * (7 - i))) & 0x1F) % 31] for i in range(8))
    return chars[:4] + "-" + chars[4:]


def canonical_device(display):
    """O que entra no HMAC de binding: 8 chars MAIÚSCULOS sem traço (AB2C9XYZ).
    Idem AccessGate.canonicalDeviceCode — traço/espaço/caixa são ruído."""
    s = re.sub(r"[^A-Za-z0-9]", "", display).upper()
    if len(s) != 8 or any(c not in ALPHABET for c in s):
        raise ValueError(
            "código de aparelho inválido: '{}' — esperado XXXX-XXXX "
            "(8 chars, sem 0/O/1/I/L), igualzinho ao que o app mostra".format(display))
    return s


def device_binding(salt, display_code):
    """HMAC-SHA256(salt 16B, canônico do código) — o campo "device.hash" do JSON.
    Espelho do AccessGate: hmacSha256(deviceSalt, canonicalDeviceCode)."""
    return hmac.new(salt, canonical_device(display_code).encode("ascii"), hashlib.sha256).digest()


def pbkdf2_manual(password, salt, iters, dklen):
    """PBKDF2-HMAC-SHA256 na mão (espelho do código Kotlin do app) — só pro selftest."""
    out = b""
    block = 1
    while len(out) < dklen:
        u = hmac.new(password, salt + struct.pack(">I", block), hashlib.sha256).digest()
        t = bytearray(u)
        for _ in range(iters - 1):
            u = hmac.new(password, u, hashlib.sha256).digest()
            for k in range(len(t)):
                t[k] ^= u[k]
        out += bytes(t)
        block += 1
    return out[:dklen]


def load_pepper(override):
    if override:
        return bytes.fromhex(override.replace(" ", ""))
    env = os.environ.get("TUNE_PEPPER", "").strip()
    if env:
        return bytes.fromhex(env.replace(" ", ""))
    raise SystemExit(
        "PEPPER é obrigatório: passe --pepper <hex> ou exporte TUNE_PEPPER.\n"
        "Este script NUNCA cria nem grava pepper — o segredo mora só com o autor."
    )


def load_json(path):
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    return {
        "version": 1,
        "updatedAt": "",
        "nota": "Senha usada = queimada: apague a entrada inteira (de { até }). "
                "O aparelho que já liberou continua funcionando. "
                "Entrada com 'device' só destrava NO aparelho daquele código.",
        "hashes": [],
    }


def save_json(path, data):
    data["updatedAt"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def main():
    ap = argparse.ArgumentParser(description="Gerador de senhas de convite do TuneGrab")
    ap.add_argument("--name", action="append", default=[], help="nome completo (repetível)")
    ap.add_argument("--device", action="append", default=[],
                    help="código do aparelho XXXX-XXXX que o app mostra na tela de "
                         "senha — prende a senha nele (repetível: 1 pra todos, ou "
                         "na ordem dos --name)")
    ap.add_argument("--show", action="store_true", help="só mostrar a senha do nome (não mexe no JSON)")
    ap.add_argument("--remove", action="append", default=[], help="id pra queimar (repetível)")
    ap.add_argument("--json", default=DEFAULT_JSON, help="caminho do passwords.json")
    ap.add_argument("--pepper", default=None, help="pepper em hex (ou env TUNE_PEPPER) — obrigatório")
    ap.add_argument("--iters", type=int, default=DEFAULT_ITERS)
    ap.add_argument("--quiet", action="store_true", help="não imprimir senhas (só IDs)")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        # 1) PBKDF2 manual == hashlib (garante que o Kotlin espelha o mesmo algoritmo)
        for iters in (1, 2, 7, 100):
            salt = secrets.token_bytes(16)
            pw = secrets.token_bytes(11)
            ref = hashlib.pbkdf2_hmac("sha256", pw, salt, iters, dklen=40)
            assert pbkdf2_manual(pw, salt, iters, 40) == ref, "PBKDF2 divergente em %d iters" % iters
        # 2) derivação determinística
        p = b"x" * 32
        a = derive_password(normalize_name("  María  da SILVA Pereira "), p)
        b = derive_password(normalize_name("maria da silva pereira"), p)
        assert a == b, "derivação não é determinística"
        # 3) formato e alfabeto
        display, canonical = a
        assert re.fullmatch(r"TUNE-[%s]{4}-[%s]{4}" % (ALPHABET, ALPHABET), display), display
        assert canonical == display.replace("-", ""), canonical
        # 4) normalização com acento colapsa pra mesma senha
        assert derive_password(normalize_name("José Ávila"), p) == derive_password(normalize_name("JOSE AVILA"), p)
        # 5) código de aparelho: formato, insensibilidade a caixa/traço/espaço
        c1 = device_code_from("9774d56d682e549c")
        c2 = device_code_from(" 9774D56D682E549C ")
        assert c1 == c2, "código de aparelho não é estável p/ caixa/espaço"
        assert re.fullmatch(r"[%s]{4}-[%s]{4}" % (ALPHABET, ALPHABET), c1), c1
        assert canonical_device("ab2c-9xyz") == "AB2C9XYZ", "canônico do código quebrou"
        # 6) binding do aparelho: bate com o HMAC puro e reprova outro código
        dsalt = secrets.token_bytes(16)
        h = device_binding(dsalt, c1)
        assert h == hmac.new(dsalt, canonical_device(c1).encode("ascii"), hashlib.sha256).digest()
        assert device_binding(dsalt, c2) == h, "binding divergiu p/ mesmo código"
        assert device_binding(dsalt, "AAAA-AAAA") != h, "binding colidiu"
        print("selftest OK — PBKDF2 batendo com hashlib, derivação determinística, "
              "formato e alfabeto certos, código de aparelho e binding confere")
        return

    pepper = load_pepper(args.pepper)

    if args.show and args.name:
        for raw_name in args.name:
            norm = normalize_name(raw_name)
            display, _ = derive_password(norm, pepper)
            print("{}  ->  {}".format(norm, display))
        return

    data = load_json(args.json)

    # Pareamento --name x --device: 1 código pra todos, ou um por nome NA ORDEM.
    devices = [canonical_device(d) for d in args.device]  # valida cedo (erro claro)
    if devices and len(devices) != 1 and len(devices) != len(args.name):
        raise SystemExit(
            "--device: use 1 código pra todos os --name, ou um por nome na ordem "
            "(recebi {} nome(s) e {} código(s))".format(len(args.name), len(devices)))

    for rid in args.remove:
        before = len(data["hashes"])
        data["hashes"] = [e for e in data["hashes"] if e.get("id") != rid]
        if len(data["hashes"]) == before:
            print("aviso: id '{}' não estava na lista".format(rid), file=sys.stderr)
        else:
            print("queimada: {} removida da lista (aparelhos já liberados seguem funcionando)".format(rid))

    new_entries = []
    for idx, raw_name in enumerate(args.name):
        norm = normalize_name(raw_name)
        display, canonical = derive_password(norm, pepper)
        salt, dk = hash_password(canonical, args.iters)
        entry_id = slugify(norm)
        taken = {e["id"] for e in data["hashes"]} | {e["id"] for e in new_entries}
        if entry_id in taken:
            raise SystemExit(
                "id '{}' já existe na lista — se for outra pessoa com mesmo nome, "
                "decida o sufixo na mão; se for re-geração, use --remove antes.".format(entry_id)
            )
        entry = {"id": entry_id, "salt": base64.b64encode(salt).decode(),
                 "hash": base64.b64encode(dk).decode(), "iters": args.iters}
        dev = devices[0] if len(devices) == 1 else (devices[idx] if devices else None)
        if dev:
            dsalt = secrets.token_bytes(16)
            entry["device"] = {"salt": base64.b64encode(dsalt).decode(),
                               "hash": base64.b64encode(device_binding(dsalt, dev)).decode()}
        new_entries.append(entry)
        tag = "   [presa no aparelho {}]".format(dev) if dev else "   [SOLTA — vale em qualquer aparelho]"
        print("{}  ->  {}   (id: {}){}".format(norm, display, entry_id, tag))

    if new_entries:
        data["hashes"].extend(new_entries)
        save_json(args.json, data)
        print("\n{} entrada(s) gravada(s) em {}".format(len(new_entries), args.json))
        print("A SENHA NUNCA VAI PRO REPO — só o hash. Guarde a senha acima AGORA")
        print("(ou recupere depois com: --show --name \"<nome>\").")
        if devices:
            print("Senha(s) com binding: SÓ destravam no aparelho do código informado —")
            print("reset de fábrica/troca de celular pede re-geração com o código novo.")
    elif args.remove:
        save_json(args.json, data)
        print("lista atualizada: {} senha(s) ativa(s)".format(len(data["hashes"])))


if __name__ == "__main__":
    main()
