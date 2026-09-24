# 단어포켓 서버 없는 동기화

`tools/sync_from_connected_device.ps1`을 실행하면 USB로 연결된 태블릿의 현재
SQLite를 이 폴더로 복사하고, 다른 기기의 단어포켓에서 불러올 수 있는
`word_pocket-progress.tsv`를 만듭니다.

- `tablet-word_pocket.db`: 연결된 태블릿의 SQLite 스냅샷
- `word_pocket-progress.tsv`: 설정 → 기록 불러오기에서 사용할 이동용 파일

이 방식은 실시간 동기화가 아닙니다. 태블릿 상태가 바뀔 때마다 스크립트를 다시
실행해야 하며, 다른 기기에서는 최신 TSV 파일을 다시 불러와야 합니다.
