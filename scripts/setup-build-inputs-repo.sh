#!/usr/bin/env bash
# ============================================================================
# setup-build-inputs-repo.sh — configura o repositório PRIVADO de inputs do
# build (a ROM usada no codegen) e o secret que o CI usa para baixá-lo.
#
# Uso:
#   GH_TOKEN=ghp_xxx ROM_ZIP=caminho/para/Donkey.Kong.64.zip \
#     ./scripts/setup-build-inputs-repo.sh [--purge-public]
#
#   ROM_ZIP aceita:
#     - um .zip contendo a ROM (asset igual ao usado pelo CI: Donkey.Kong.64.zip)
#     - ou diretamente o .z64/.n64/.v64 (o script empacota com o nome correto)
#
# O que o script faz:
#   1) Normaliza .z64/.n64/.v64 para z64 e valida DK64 NTSC-U 1.0
#   2) Cria (se não existir) o repo PRIVADO $PRIVATE_REPO
#   3) Publica/atualiza o release `build-inputs` com o asset `Donkey.Kong.64.zip`
#   4) Define o secret PRIVATE_REPO_TOKEN no repo público do port
#   5) Com --purge-public: apaga release/tag `build-inputs` antigos do repo
#      PÚBLICO (a ROM não deve ficar acessível no repo público)
#
# Requisitos: gh CLI (https://cli.github.com), python3, zip e unzip no PATH.
# Segurança: o token é usado apenas localmente pelo gh e gravado como secret
# no GitHub; o script nunca o imprime.
# ============================================================================
set -euo pipefail

GH_TOKEN="${GH_TOKEN:?Exporte GH_TOKEN=<PAT classic com escopo repo>}"
ROM_ZIP="${ROM_ZIP:?Exporte ROM_ZIP=caminho/para/Donkey.Kong.64.zip (ou do .z64 direto)}"

if [ -z "${PUBLIC_REPO:-}" ]; then
    PUBLIC_REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)"
fi
PUBLIC_REPO="${PUBLIC_REPO:-deivid22srk/dk64-recomp-android}"
REPO_OWNER="${PUBLIC_REPO%%/*}"
PRIVATE_REPO="${PRIVATE_REPO:-${REPO_OWNER}/dk64-recomp-build-inputs}"
TAG="build-inputs"
ASSET="Donkey.Kong.64.zip"

command -v gh    >/dev/null 2>&1 || { echo "erro: gh CLI não encontrado (https://cli.github.com)"; exit 1; }
command -v zip   >/dev/null 2>&1 || { echo "erro: zip não encontrado"; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo "erro: unzip não encontrado"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "erro: python3 não encontrado"; exit 1; }
export GH_TOKEN

if [ ! -f "$ROM_ZIP" ]; then
    echo "erro: arquivo não encontrado: $ROM_ZIP"; exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ----------------------------------------------------------------------------
# 1) Extrai, normaliza byte order -> z64 e recria o asset canônico
# ----------------------------------------------------------------------------
mkdir -p "$WORK/check"
case "$ROM_ZIP" in
    *.zip)
        unzip -q -o "$ROM_ZIP" -d "$WORK/check"
        ;;
    *.z64|*.n64|*.v64)
        cp "$ROM_ZIP" "$WORK/check/$(basename "$ROM_ZIP")"
        ;;
    *)
        echo "erro: ROM_ZIP deve ser .zip, .z64, .n64 ou .v64"; exit 1
        ;;
esac

ROM_FILE="$(find "$WORK/check" -type f \( -iname '*.z64' -o -iname '*.n64' -o -iname '*.v64' \) | head -1)"
if [ -z "$ROM_FILE" ]; then
    echo "erro: nenhum .z64/.n64/.v64 encontrado"; exit 1
fi
N_ROMS="$(find "$WORK/check" -type f \( -iname '*.z64' -o -iname '*.n64' -o -iname '*.v64' \) | wc -l)"
if [ "$N_ROMS" -ne 1 ]; then
    echo "erro: esperava exatamente 1 ROM, achei $N_ROMS"; exit 1
fi

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
CANONICAL="$WORK/Donkey Kong 64 (USA).z64"
python3 "$SCRIPT_DIR/normalize-dk64-rom.py" "$ROM_FILE" "$CANONICAL"

rm -f "$WORK/$ASSET"
zip -q -j "$WORK/$ASSET" "$CANONICAL"
echo "==> Asset canônico preparado: $ASSET"

# ----------------------------------------------------------------------------
# 2) Cria o repo privado (se não existir)
# ----------------------------------------------------------------------------
if gh repo view "$PRIVATE_REPO" >/dev/null 2>&1; then
    echo "==> Repo privado já existe: $PRIVATE_REPO"
else
    echo "==> Criando repo privado: $PRIVATE_REPO"
    gh repo create "$PRIVATE_REPO" --private \
        --description "Inputs privados do build do port Android DK64-Recompiled (NÃO redistribuir)"
fi

# Releases exigem pelo menos 1 commit — bootstrap de README se o repo estiver vazio
if ! gh api "repos/$PRIVATE_REPO/commits?per_page=1" >/dev/null 2>&1; then
    echo "==> Repo vazio: criando README inicial"
    gh api -X PUT "repos/$PRIVATE_REPO/contents/README.md" \
        -f message="init: inputs privados do build" \
        -f content="$(printf '# build-inputs\n\nInputs privados do build do port Android DK64-Recompiled (ROM de codegen).\nNÃO redistribuir.\n' | base64 -w0)" >/dev/null
fi

# ----------------------------------------------------------------------------
# 3) Publica/atualiza o release build-inputs com o asset
# ----------------------------------------------------------------------------
if gh release view "$TAG" --repo "$PRIVATE_REPO" >/dev/null 2>&1; then
    echo "==> Release $TAG já existe em $PRIVATE_REPO (asset será substituído)"
else
    echo "==> Criando release $TAG em $PRIVATE_REPO"
    gh release create "$TAG" --repo "$PRIVATE_REPO" --title "$TAG" \
        --notes "Inputs para o codegen do CI do port Android. Material protegido — NÃO redistribuir."
fi
gh release upload "$TAG" "$WORK/$ASSET" --repo "$PRIVATE_REPO" --clobber
echo "==> Asset publicado: $PRIVATE_REPO :: $TAG :: $ASSET"

# ----------------------------------------------------------------------------
# 4) Secret PRIVATE_REPO_TOKEN no repo público
# ----------------------------------------------------------------------------
echo "==> Definindo secret PRIVATE_REPO_TOKEN em $PUBLIC_REPO"
gh secret set PRIVATE_REPO_TOKEN --repo "$PUBLIC_REPO" --body "$GH_TOKEN"

# ----------------------------------------------------------------------------
# 5) Limpa releases antigos do repo público (--purge-public)
# ----------------------------------------------------------------------------
if [ "${1:-}" = "--purge-public" ]; then
    if gh release view "$TAG" --repo "$PUBLIC_REPO" >/dev/null 2>&1; then
        echo "==> Removendo release/tag $TAG do repo PÚBLICO $PUBLIC_REPO"
        gh release delete "$TAG" --repo "$PUBLIC_REPO" --yes --cleanup-tag
    else
        echo "==> Repo público sem release $TAG (nada a remover)"
    fi
fi

echo
echo "Pronto. Fluxo do CI:"
echo "  build.yml -> gh release download $TAG --repo $PRIVATE_REPO (via secret PRIVATE_REPO_TOKEN)"
echo "Se você rotacionar o PAT, rode este script novamente para atualizar o secret."
