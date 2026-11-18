# 仕様の参照と試験結果

要件・設計・試験の対応は[詳細設計のDD-TRACE](../specifications/03_detailed_design.md)に記載します。仕様の変更理由や影響範囲は、必要に応じて変更した文書やPRに残します。

## 結果とcommitの記録

[results.json](results.json) は個別ケースの記録配列です。実装担当者は実施後、関連要求・試験、実際に検査した40桁のcommit、入力/操作/期待値と結果を説明する証跡、制約を追加します。`case_id`はケースと実行を識別する一意な名前にします。同じケースの再実施は別の記録として追加し、失敗を上書きして消しません。

| フィールド | 記入内容 |
|---|---|
| `requirement_ids`, `test_ids` | CURRENTの実在ID。要求ごとに関連TESTを少なくとも1つ含める |
| `stage` | CURRENT / PUBLIC。PUBLICのPASSで私用ケースを埋めない |
| `outcome` | PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE |
| `execution` | EXECUTED / NOT_RUN / UNESTABLISHED（環境・経路等が不成立） |
| `permission` | GRANTED / NOT_REQUIRED / UNKNOWN / DENIED（そのケースの用途別判定） |
| `reason`, `evidence`, `limitations` | 結果の理由、ログ/PR/測定資料の参照配列、未確認の範囲。理由と制約は空欄不可 |

PASSはEXECUTED・GRANTEDまたはNOT_REQUIRED・非空の証跡が揃う場合だけ受け付けます。用途未許可・未実施・不成立をPASSにしません。ホスト上の台帳検査では外部サービス操作がないためNOT_REQUIREDですが、その結果をサービス利用許可へ流用しません。

未記録は結果不明です。既存PRの成果を未実施や失敗に書き換える意味ではありません。記録は個別ケースの結果に限定し、要求・試験ファミリー・製品全体の自動合格は出しません。完成レビューではDD-RELEASE-01に従って全私用ケース、導入条件、用途許可、実機、私用配備・復旧等を別に確認します。

## ローカルでの確認

必要に応じて、現行の仕様からIDと対応関係を読み取り、参照先や記録済みの結果を検査できます。

```sh
python3 scripts/traceability/check.py
python3 -m unittest discover -s scripts/traceability -p 'test_*.py'
```

## 決定履歴と残る確認

以下は既存原文からの引継ぎです。#18では新しい解釈を承認済みにしていません。

| 追跡名 | 状態・根拠 | 担当先・再開箇所 |
|---|---|---|
| T18-01 旧件数・一般公開条件 | HISTORY。[現在地](../specifications/00_review_status.md)の233/231/229件、89/90要求、41/43試験は変更時点が異なる。現行は1.0.1の90/229/43。旧レビューの公開条件はPUBLICへの分離で置換 | 範囲変更時は利用者承認を記録。#153の完成レビューで旧条件の混入を確認 |
| T18-02 廃止設計への残存参照 | 解消。FR-SRC-09から廃止済みのDD-PROV-06への参照を削除。 | [DD-TRACE](../specifications/03_detailed_design.md) |
| T18-03 最終ナビゲーション | OPEN。[UI設計§4.1](../design.md)はPoCのLibrary中心、基本設計はHome中心の案。原文で最終構成は未確定 | #19/#64/#149で差分・理由を記録。#18で最終案を選ばない |
| T18-04 履歴の計測表現 | OPEN。DD-HIST-01は音声ポジションと状態に基づく推定と記述し、参照先[技術判断](../specifications/08_design_decisions.md)は単調時計の聴取区間と音源区間を分離。シーク・Pause・時計変更を加算しない点は共通 | #78/#111/#139の具体ケースで差を確認。新しい計算式を#18で採用しない |
| T18-05 私用配備 | BLOCKED_INPUT。P-02/P-03のDebian/Docker版・接続URL/HTTPS終端・既存構成・バックアップ先は未確認。保持方針の決定と実配備成功は別 | #53/#144。構成案作成後、実配備前に対象と承認を確認。秘密・私用URLを公開台帳へ記入しない |
| T18-06 実機・用途許可 | NOT_VERIFIED。P-04のADB serial・M21 OS/firmware・DAC能力・測定設備、R-06の用途別許可等は未確認 | #26/#150/#152/#153と各provider Issue。NDK/ABI・実機音声・私用復旧をホスト検査の成功で埋めない |

#18の結果と検証commitは`results.json`と[検証記録](validation.md)へ保存します。PRレビューとリモートCIの結果は対応PRで追跡します。
