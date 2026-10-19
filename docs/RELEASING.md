# Release signing — One Video G

## Estado atual

- O keystore de release (`one-video-g-release.keystore`, alias `one-video-g`, RSA 2048, validade 30 anos) foi **gerado uma única vez** e **nunca é commitado** no repositório.
- As credenciais vivem apenas em:
  1. **GitHub Secrets** (valores encriptados): `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
  2. **Backup local do dono do projeto** (ficheiro `.keystore` + `credentials.txt`, fora do git).
- O CI decodifica o keystore para `$RUNNER_TEMP` em cada build e assina o APK release; depois verifica a assinatura com `apksigner verify` e **só publica se a verificação passar**.

## Backup do keystore (FAÇA ISTO AGORA)

1. Guarde o ficheiro `one-video-g-release.keystore` e as palavras-passe (`KS_PASS`, `KEY_PASS`) num gestor de palavras-passe **e** numa segunda localização offline (pen USB, disco externo).
2. **Se perder o keystore, não poderá atualizar a app com a mesma assinatura** (utilizadores teriam de desinstalar). Não existe recuperação.
3. Nunca faça commit do keystore nem partilhe as palavras-passe em chats/tickets. Se suspeitar de exposição: crie um keystore novo, atualize os 4 segredos e planeie uma migração de assinatura.

## Rodar o processo manualmente

- GitHub → **Actions** → **Android Release APK** → **Run workflow** (em qualquer branch; a publicação da release só acontece em `main`).

## Rodar localmente (opcional)

```bash
export ANDROID_KEYSTORE_FILE=/caminho/one-video-g-release.keystore
export ANDROID_KEYSTORE_PASSWORD=KS_PASS
export ANDROID_KEY_ALIAS=one-video-g
export ANDROID_KEY_PASSWORD=KEY_PASS
./gradlew assembleRelease
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Rotação de emergência

1. `keytool -genkeypair -v -keystore novo.keystore -alias one-video-g ...`
2. Atualizar `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD` nos segredos.
3. Documentar a quebra de assinatura no README (nota para utilizadores).
