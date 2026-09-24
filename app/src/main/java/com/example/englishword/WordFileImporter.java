package com.example.englishword;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.SAXParserFactory;

public final class WordFileImporter {
    private WordFileImporter() { }

    public static WordDatabase.ImportResult importUri(Context context, Uri uri, WordDatabase database) throws Exception {
        String name = displayName(context, uri).toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx")) return importXlsx(context, uri, database);
        if (name.endsWith(".xls")) throw new IllegalArgumentException("옛날 .xls 형식은 지원하지 않습니다. 엑셀에서 .xlsx로 저장해 주세요.");
        char delimiter = name.endsWith(".csv") ? ',' : '\t';
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("선택한 파일을 열 수 없습니다.");
            return database.importRows(consumer -> parseDelimited(input, delimiter, consumer));
        }
    }

    private static WordDatabase.ImportResult importXlsx(Context context, Uri uri, WordDatabase database) throws Exception {
        File temp = File.createTempFile("word_pocket_import_", ".xlsx", context.getCacheDir());
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temp)) {
                if (input == null) throw new IllegalArgumentException("선택한 엑셀 파일을 열 수 없습니다.");
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            return database.importRows(consumer -> parseXlsx(temp, consumer));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void parseDelimited(InputStream input, char delimiter, WordDatabase.RowConsumer consumer) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new BufferedInputStream(input), StandardCharsets.UTF_8));
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean header = true;
        int value;
        while ((value = reader.read()) != -1) {
            char ch = (char) value;
            if (ch == '"') {
                if (quoted) {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') field.append('"');
                    else { quoted = false; if (next != -1) reader.reset(); }
                } else if (field.length() == 0) quoted = true;
                else field.append(ch);
            } else if (ch == delimiter && !quoted) {
                row.add(field.toString()); field.setLength(0);
            } else if ((ch == '\n' || ch == '\r') && !quoted) {
                if (ch == '\r') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next != '\n' && next != -1) reader.reset();
                }
                row.add(field.toString()); field.setLength(0);
                if (header) header = false;
                else if (!isBlank(row)) consumer.accept(row.toArray(new String[0]));
                row.clear();
            } else field.append(ch);
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            if (!header && !isBlank(row)) consumer.accept(row.toArray(new String[0]));
        }
    }

    private static boolean isBlank(List<String> row) {
        for (String value : row) if (value != null && !value.trim().isEmpty()) return false;
        return true;
    }

    private static void parseXlsx(File file, WordDatabase.RowConsumer consumer) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            List<String> sharedStrings = readSharedStrings(zip);
            ZipEntry sheet = firstWorksheet(zip);
            if (sheet == null) throw new IllegalArgumentException("엑셀 파일에서 첫 번째 시트를 찾지 못했습니다.");
            try (InputStream input = zip.getInputStream(sheet)) {
                parseXml(input, new SheetHandler(sharedStrings, consumer));
            }
        }
    }

    private static List<String> readSharedStrings(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) return Collections.emptyList();
        List<String> strings = new ArrayList<>();
        try (InputStream input = zip.getInputStream(entry)) {
            parseXml(input, new DefaultHandler() {
                StringBuilder value;
                boolean inText;
                @Override public void startElement(String uri, String local, String qName, Attributes attributes) {
                    if ("si".equals(qName)) value = new StringBuilder();
                    if ("t".equals(qName)) inText = true;
                }
                @Override public void characters(char[] ch, int start, int length) {
                    if (inText && value != null) value.append(ch, start, length);
                }
                @Override public void endElement(String uri, String local, String qName) {
                    if ("t".equals(qName)) inText = false;
                    if ("si".equals(qName)) strings.add(value == null ? "" : value.toString());
                }
            });
        }
        return strings;
    }

    private static ZipEntry firstWorksheet(ZipFile zip) {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.matches("xl/worksheets/sheet\\d+\\.xml")) names.add(name);
        }
        Collections.sort(names);
        return names.isEmpty() ? null : zip.getEntry(names.get(0));
    }

    private static void parseXml(InputStream input, DefaultHandler handler) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        try { reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        try { reader.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) { }
        try { reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) { }
        reader.setContentHandler(handler);
        reader.parse(new InputSource(input));
    }

    private static final class SheetHandler extends DefaultHandler {
        private final List<String> sharedStrings;
        private final WordDatabase.RowConsumer consumer;
        private final String[] row = new String[9];
        private final StringBuilder value = new StringBuilder();
        private boolean header = true;
        private boolean capture;
        private int column = -1;
        private String type = "";

        SheetHandler(List<String> sharedStrings, WordDatabase.RowConsumer consumer) {
            this.sharedStrings = sharedStrings; this.consumer = consumer;
        }

        @Override public void startElement(String uri, String local, String qName, Attributes attrs) {
            if ("row".equals(qName)) for (int i = 0; i < row.length; i++) row[i] = "";
            if ("c".equals(qName)) {
                column = columnIndex(attrs.getValue("r"));
                type = attrs.getValue("t") == null ? "" : attrs.getValue("t");
                value.setLength(0);
            }
            if ("v".equals(qName) || ("t".equals(qName) && "inlineStr".equals(type))) capture = true;
        }

        @Override public void characters(char[] ch, int start, int length) {
            if (capture) value.append(ch, start, length);
        }

        @Override public void endElement(String uri, String local, String qName) throws org.xml.sax.SAXException {
            if ("v".equals(qName) || "t".equals(qName)) capture = false;
            if ("c".equals(qName) && column >= 0 && column < row.length) {
                String text = value.toString();
                if ("s".equals(type)) {
                    try { text = sharedStrings.get(Integer.parseInt(text)); } catch (Exception ignored) { }
                }
                row[column] = text;
            }
            if ("row".equals(qName)) {
                if (header) header = false;
                else {
                    try { consumer.accept(row.clone()); }
                    catch (Exception e) { throw new org.xml.sax.SAXException(e); }
                }
            }
        }

        private int columnIndex(String reference) {
            if (reference == null) return -1;
            int result = 0;
            for (int i = 0; i < reference.length(); i++) {
                char c = reference.charAt(i);
                if (c < 'A' || c > 'Z') break;
                result = result * 26 + (c - 'A' + 1);
            }
            return result - 1;
        }
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() == null ? "words.tsv" : uri.getLastPathSegment();
    }
}
