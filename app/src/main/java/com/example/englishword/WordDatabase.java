package com.example.englishword;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Reader;
import java.io.Writer;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public final class WordDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "wordly.db";
    private static final int DB_VERSION = 4;
    private final Context appContext;

    public interface RowConsumer { void accept(String[] fields) throws Exception; }
    public interface RowProducer { void produce(RowConsumer consumer) throws Exception; }

    public static final class ImportResult {
        public int imported;
        public int skipped;
        public int invalid;
    }

    public static final class ProgressResult {
        public int restored;
        public int missing;
        public int invalid;
    }

    public WordDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        appContext = context.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE words (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "word TEXT NOT NULL COLLATE NOCASE," +
                "part_of_speech TEXT NOT NULL DEFAULT ''," +
                "meaning TEXT NOT NULL DEFAULT ''," +
                "pronunciation TEXT NOT NULL DEFAULT ''," +
                "example1 TEXT NOT NULL DEFAULT ''," +
                "example1_meaning TEXT NOT NULL DEFAULT ''," +
                "example2 TEXT NOT NULL DEFAULT ''," +
                "example2_meaning TEXT NOT NULL DEFAULT ''," +
                "memo TEXT NOT NULL DEFAULT ''," +
                "favorite INTEGER NOT NULL DEFAULT 0," +
                "mastery INTEGER NOT NULL DEFAULT 0," +
                "mastered_at INTEGER NOT NULL DEFAULT 0," +
                "source_key TEXT UNIQUE," +
                "created_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))) ");
        createIndexes(db);
        importBundledWords(db, false);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE words ADD COLUMN source_key TEXT");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_words_source_key ON words(source_key)");
            importBundledWords(db, true);
        }
        if (oldVersion >= 2 && oldVersion < 3) {
            importBundledWords(db, false);
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE words ADD COLUMN mastered_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_words_mastered_at ON words(mastered_at)");
        }
    }

    private void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX idx_words_word ON words(word COLLATE NOCASE)");
        db.execSQL("CREATE INDEX idx_words_mastery ON words(mastery)");
        db.execSQL("CREATE INDEX idx_words_mastered_at ON words(mastered_at)");
        db.execSQL("CREATE UNIQUE INDEX idx_words_source_key ON words(source_key)");
    }

    private void importBundledWords(SQLiteDatabase db, boolean matchLegacyRows) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                appContext.getAssets().open("initial_words.tsv"), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                insertImportedRow(db, line.split("\\t", -1), matchLegacyRows);
            }
        } catch (Exception e) {
            throw new IllegalStateException("기본 단어 파일을 읽지 못했습니다.", e);
        }
    }

    public ImportResult importRows(RowProducer producer) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        ImportResult result = new ImportResult();
        db.beginTransaction();
        try {
            producer.produce(fields -> {
                if (fields == null || fields.length < 3 || clean(fields[0]).isEmpty()) {
                    result.invalid++;
                    return;
                }
                if (insertImportedRow(db, fields, false)) result.imported++; else result.skipped++;
            });
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    private boolean insertImportedRow(SQLiteDatabase db, String[] raw, boolean matchLegacyRows) {
        String[] fields = new String[9];
        for (int i = 0; i < fields.length; i++) fields[i] = i < raw.length ? clean(raw[i]) : "";
        if (fields[0].isEmpty()) return false;
        String sourceKey = hash(fields);

        if (matchLegacyRows) {
            ContentValues keyOnly = new ContentValues();
            keyOnly.put("source_key", sourceKey);
            int matchedExisting = db.update("words", keyOnly,
                    "source_key IS NULL AND word=? AND part_of_speech=? AND meaning=? AND pronunciation=? " +
                            "AND example1=? AND example1_meaning=? AND example2=? AND example2_meaning=? AND memo=?",
                    fields);
            if (matchedExisting > 0) return true;
        }

        ContentValues values = valuesFrom(fields);
        values.put("source_key", sourceKey);
        return db.insertWithOnConflict("words", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public long addWord(Word word) {
        String[] fields = new String[]{word.word, word.partOfSpeech, word.meaning, word.pronunciation,
                word.example1, word.example1Meaning, word.example2, word.example2Meaning, word.memo};
        ContentValues values = valuesFrom(fields);
        values.put("source_key", hash(fields));
        return getWritableDatabase().insertWithOnConflict("words", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private ContentValues valuesFrom(String[] fields) {
        ContentValues values = new ContentValues();
        values.put("word", clean(fields[0]));
        values.put("part_of_speech", clean(fields[1]));
        values.put("meaning", clean(fields[2]));
        values.put("pronunciation", clean(fields[3]));
        values.put("example1", clean(fields[4]));
        values.put("example1_meaning", clean(fields[5]));
        values.put("example2", clean(fields[6]));
        values.put("example2_meaning", clean(fields[7]));
        values.put("memo", clean(fields[8]));
        return values;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace("\uFEFF", "").trim();
    }

    private static String hash(String[] fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                digest.update(clean(field).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1F);
            }
            byte[] bytes = digest.digest();
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Word> search(String query, int limit) {
        return search(query, limit, 0, false);
    }

    public List<Word> search(String query, int limit, int offset, boolean masteredOnly) {
        List<Word> words = new ArrayList<>();
        String normalized = query == null ? "" : query.trim();
        String queryPart = normalized.isEmpty() ? null : "(word LIKE ? OR meaning LIKE ?)";
        String selection;
        if (masteredOnly && queryPart != null) selection = "mastery >= 100 AND " + queryPart;
        else if (masteredOnly) selection = "mastery >= 100";
        else selection = queryPart;
        String[] args = normalized.isEmpty() ? null : new String[]{normalized + "%", "%" + normalized + "%"};
        try (Cursor cursor = getReadableDatabase().query("words", null, selection, args, null, null,
                "favorite DESC, word COLLATE NOCASE ASC", offset + "," + limit)) {
            while (cursor.moveToNext()) words.add(fromCursor(cursor));
        }
        return words;
    }

    public List<Word> searchToday(String query) {
        return searchToday(query, 20);
    }

    public List<Word> searchToday(String query, int dailyGoal) {
        List<Word> words = new ArrayList<>();
        Calendar calendar = Calendar.getInstance();
        long seed = calendar.get(Calendar.YEAR) * 1000L + calendar.get(Calendar.DAY_OF_YEAR);
        String normalized = query == null ? "" : query.trim();
        String sql = "SELECT * FROM (SELECT * FROM words " +
                "WHERE mastery < 100 OR mastered_at >= ? " +
                "ORDER BY abs((id * 1103515245 + ?) % 2147483647) LIMIT " + Math.max(1, Math.min(1000, dailyGoal)) + ")";
        String[] args;
        if (normalized.isEmpty()) args = new String[]{String.valueOf(startOfTodaySeconds()), String.valueOf(seed)};
        else {
            sql += " WHERE word LIKE ? OR meaning LIKE ?";
            args = new String[]{String.valueOf(startOfTodaySeconds()), String.valueOf(seed),
                    normalized + "%", "%" + normalized + "%"};
        }
        sql += " ORDER BY word COLLATE NOCASE ASC";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) words.add(fromCursor(cursor));
        }
        return words;
    }

    public int countWords() { return countWhere(null); }
    public int countMastered() { return countWhere("mastery >= 100"); }
    public int countMasteredToday() {
        return countWhere("mastery >= 100 AND mastered_at >= " + startOfTodaySeconds());
    }
    public int countStudyCandidatesToday() {
        return countWhere("mastery < 100 OR mastered_at >= " + startOfTodaySeconds());
    }

    private int countWhere(String selection) {
        try (Cursor cursor = getReadableDatabase().query("words", new String[]{"COUNT(*)"},
                selection, null, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public void updateMastery(long id, int mastery) {
        ContentValues values = new ContentValues();
        int normalized = Math.max(0, Math.min(100, mastery));
        values.put("mastery", normalized);
        values.put("mastered_at", normalized >= 100 ? System.currentTimeMillis() / 1000L : 0);
        getWritableDatabase().update("words", values, "id=?", new String[]{String.valueOf(id)});
    }

    public void markMastered(List<Word> words) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("mastery", 100);
        values.put("mastered_at", System.currentTimeMillis() / 1000L);
        db.beginTransaction();
        try {
            for (Word word : words) {
                db.update("words", values, "id=?", new String[]{String.valueOf(word.id)});
                word.mastery = 100;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public int exportProgress(Writer output) throws Exception {
        BufferedWriter writer = output instanceof BufferedWriter
                ? (BufferedWriter) output : new BufferedWriter(output);
        writer.write("WORD_POCKET_PROGRESS_V1\n");
        writer.write("source_key\tword\tpart_of_speech\tmeaning\tpronunciation\texample1\t" +
                "example1_meaning\texample2\texample2_meaning\tmemo\tfavorite\tmastery\n");
        int count = 0;
        try (Cursor cursor = getReadableDatabase().query("words",
                new String[]{"source_key", "word", "part_of_speech", "meaning", "pronunciation",
                        "example1", "example1_meaning", "example2", "example2_meaning", "memo",
                        "favorite", "mastery"},
                null, null, null, null, "id ASC")) {
            while (cursor.moveToNext()) {
                for (int i = 0; i < 10; i++) {
                    if (i > 0) writer.write('\t');
                    writer.write(backupClean(cursor.getString(i)));
                }
                writer.write('\t');
                writer.write(String.valueOf(cursor.getInt(10)));
                writer.write('\t');
                writer.write(String.valueOf(cursor.getInt(11)));
                writer.write('\n');
                count++;
            }
        }
        writer.flush();
        return count;
    }

    public ProgressResult importProgress(Reader input) throws Exception {
        ProgressResult result = new ProgressResult();
        BufferedReader reader = input instanceof BufferedReader
                ? (BufferedReader) input : new BufferedReader(input);
        String magic = reader.readLine();
        if (!"WORD_POCKET_PROGRESS_V1".equals(clean(magic))
                && !"WORDLY_PROGRESS_V1".equals(clean(magic))) {
            throw new IllegalArgumentException("단어포켓 학습 기록 파일이 아닙니다.");
        }
        reader.readLine();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] columns = line.split("\\t", -1);
                if (columns.length < 12 || clean(columns[1]).isEmpty()) {
                    result.invalid++;
                    continue;
                }
                String originalKey = clean(columns[0]);
                String[] fields = new String[9];
                System.arraycopy(columns, 1, fields, 0, fields.length);
                String lookupKey = originalKey;
                int favorite;
                int mastery;
                try {
                    favorite = Integer.parseInt(clean(columns[10])) == 0 ? 0 : 1;
                    mastery = Math.max(0, Math.min(100, Integer.parseInt(clean(columns[11]))));
                } catch (NumberFormatException e) {
                    result.invalid++;
                    continue;
                }
                if (lookupKey.isEmpty() || !hasSourceKey(db, lookupKey)) {
                    insertImportedRow(db, fields, false);
                    lookupKey = hash(fields);
                }
                ContentValues values = new ContentValues();
                values.put("favorite", favorite);
                values.put("mastery", mastery);
                int updated = db.update("words", values, "source_key=?", new String[]{lookupKey});
                if (updated > 0) result.restored++; else result.missing++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return result;
    }

    private boolean hasSourceKey(SQLiteDatabase db, String sourceKey) {
        try (Cursor cursor = db.query("words", new String[]{"id"}, "source_key=?",
                new String[]{sourceKey}, null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    private static String backupClean(String value) {
        return clean(value).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static long startOfTodaySeconds() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis() / 1000L;
    }

    private Word fromCursor(Cursor c) {
        Word w = new Word();
        w.id = c.getLong(c.getColumnIndexOrThrow("id"));
        w.word = c.getString(c.getColumnIndexOrThrow("word"));
        w.partOfSpeech = c.getString(c.getColumnIndexOrThrow("part_of_speech"));
        w.meaning = c.getString(c.getColumnIndexOrThrow("meaning"));
        w.pronunciation = c.getString(c.getColumnIndexOrThrow("pronunciation"));
        w.example1 = c.getString(c.getColumnIndexOrThrow("example1"));
        w.example1Meaning = c.getString(c.getColumnIndexOrThrow("example1_meaning"));
        w.example2 = c.getString(c.getColumnIndexOrThrow("example2"));
        w.example2Meaning = c.getString(c.getColumnIndexOrThrow("example2_meaning"));
        w.memo = c.getString(c.getColumnIndexOrThrow("memo"));
        w.favorite = c.getInt(c.getColumnIndexOrThrow("favorite")) == 1;
        w.mastery = c.getInt(c.getColumnIndexOrThrow("mastery"));
        return w;
    }
}
