import argparse
import sqlite3
from pathlib import Path


HEADER = (
    "source_key\tword\tpart_of_speech\tmeaning\tpronunciation\texample1\t"
    "example1_meaning\texample2\texample2_meaning\tmemo\tfavorite\tmastery"
)


def clean(value):
    if value is None:
        return ""
    return str(value).replace("\t", " ").replace("\r", " ").replace("\n", " ").strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("database")
    parser.add_argument("output")
    args = parser.parse_args()

    database = Path(args.database).resolve()
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    connection = sqlite3.connect(f"file:{database.as_posix()}?mode=ro", uri=True)
    try:
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise RuntimeError(f"SQLite integrity check failed: {integrity}")
        rows = connection.execute(
            """
            SELECT source_key, word, part_of_speech, meaning, pronunciation,
                   example1, example1_meaning, example2, example2_meaning, memo,
                   favorite, mastery
            FROM words
            ORDER BY id
            """
        ).fetchall()
    finally:
        connection.close()

    temporary = output.with_suffix(output.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as writer:
        writer.write("WORD_POCKET_PROGRESS_V1\n")
        writer.write(HEADER + "\n")
        for row in rows:
            writer.write("\t".join(clean(value) for value in row) + "\n")
    temporary.replace(output)

    learned = sum(1 for row in rows if int(row[-1]) >= 100)
    favorites = sum(1 for row in rows if int(row[-2]) != 0)
    print(f"saved={len(rows)} learned={learned} favorites={favorites}")


if __name__ == "__main__":
    main()
