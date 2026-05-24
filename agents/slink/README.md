# slink-cli

SOLID VM에서 Google Colab GPU를 연결하는 CLI 도구입니다.

## 설치

```bash
pipx install slink-cli
```

## 사용법

**학기 초 1회 셋업:**
```bash
slink init
```

**매일 사용:**
```bash
slink connect
```

## 전체 흐름

1. `slink init` → 학번/이메일 입력 → API Key 발급
2. Colab Secret에 `SLINK_API_KEY` 등록
3. Colab 빈 셀에 원라이너 실행:
   ```python
   import urllib.request; exec(urllib.request.urlopen('https://slink-production-3e7d.up.railway.app/agent').read())
   ```
4. `slink connect` → VS Code에서 GPU 커널 연결
