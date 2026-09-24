package com.example.englishword;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import android.speech.tts.TextToSpeech;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.recognition.Ink;

import java.util.ArrayList;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int SECTION_HOME = 0;
    private static final int SECTION_WORDBOOK = 1;
    private static final int SECTION_REVIEW = 2;
    private static final int SECTION_SETTINGS = 3;
    private static final int PAGE_SIZE = 100;
    private static final int BG = Color.rgb(247, 248, 243);
    private static final int INK = Color.rgb(31, 43, 38);
    private static final int MUTED = Color.rgb(104, 117, 111);
    private static final int GREEN = Color.rgb(49, 92, 76);
    private static final int GREEN_SOFT = Color.rgb(226, 237, 231);
    private static final int GOLD = Color.rgb(233, 185, 73);
    private static final int BORDER = Color.rgb(224, 229, 223);
    private static final int ERROR = Color.rgb(177, 62, 62);

    private WordDatabase database;
    private WordListAdapter adapter;
    private LinearLayout listPane;
    private ScrollView detailPane;
    private LinearLayout detailContent;
    private boolean tablet;
    private boolean compactPhone;
    private boolean showingDetail;
    private Word selectedWord;
    private int currentSection = SECTION_HOME;
    private String currentQuery = "";
    private int currentOffset;
    private boolean hasMore;
    private boolean loadingMore;
    private int systemInsetLeft;
    private int systemInsetTop;
    private int systemInsetRight;
    private int systemInsetBottom;
    private ActivityResultLauncher<String[]> filePicker;
    private ActivityResultLauncher<String> progressExporter;
    private ActivityResultLauncher<String[]> progressImporter;
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private int dailyGoal = 20;
    private int todayExtraGoal;
    private boolean quizMode;
    private boolean quizComplete;
    private boolean quizAnswered;
    private int quizPhase;
    private int quizPassed;
    private int quizWrong;
    private final List<QuizQuestion> quizQueue = new ArrayList<>();
    private final List<Word> quizWords = new ArrayList<>();
    private QuizQuestion currentQuestion;
    private EditText quizAnswerInput;
    private TextView quizFeedback;
    private TextView quizAction;
    private HandwritingPad handwritingPad;
    private TextView handwritingStatus;
    private TextView handwritingClear;
    private String pendingHandwritingAnswer;
    private DigitalInkRecognizer englishHandwritingRecognizer;
    private DigitalInkRecognizer koreanHandwritingRecognizer;
    private DigitalInkRecognitionModel englishHandwritingModel;
    private DigitalInkRecognitionModel koreanHandwritingModel;
    private boolean englishHandwritingModelReady;
    private boolean koreanHandwritingModelReady;

    private static final class QuizQuestion {
        final Word word;
        final boolean askForWord;

        QuizQuestion(Word word, boolean askForWord) {
            this.word = word;
            this.askForWord = askForWord;
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        database = new WordDatabase(this);
        SharedPreferences settings = getSharedPreferences("wordly_settings", MODE_PRIVATE);
        dailyGoal = settings.getInt("daily_goal", 20);
        if (todayKey().equals(settings.getString("extra_goal_date", ""))) {
            todayExtraGoal = settings.getInt("extra_goal_count", 0);
        }
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.US);
                textToSpeech.setSpeechRate(0.85f);
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        filePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importSelectedFile);
        progressExporter = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/tab-separated-values"),
                this::exportProgress);
        progressImporter = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::importProgress);
        tablet = getResources().getConfiguration().screenWidthDp >= 840;
        compactPhone = getResources().getConfiguration().screenWidthDp < 400;
        if (tablet) initializeHandwritingRecognizers();
        View initialScreen = buildScreen();
        setContentView(initialScreen);
        ViewCompat.requestApplyInsets(initialScreen);
        reload("");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (quizMode) confirmExitQuiz();
                else if (showingDetail && !tablet) showList();
                else if (currentSection != SECTION_HOME) switchSection(SECTION_HOME);
                else finish();
            }
        });
    }

    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(systemInsetLeft, systemInsetTop, systemInsetRight, systemInsetBottom);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            systemInsetLeft = bars.left;
            systemInsetTop = bars.top;
            systemInsetRight = bars.right;
            systemInsetBottom = bars.bottom;
            view.setPadding(systemInsetLeft, systemInsetTop, systemInsetRight, systemInsetBottom);
            return insets;
        });

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(tablet ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int side = tablet ? dp(28) : dp(compactPhone ? 12 : 18);
        body.setPadding(side, 0, side, dp(8));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (currentSection == SECTION_SETTINGS) {
            listPane = buildSettingsPane();
            detailPane = buildDetailPane();
            detailPane.setVisibility(View.GONE);
            ScrollView settingsScroll = new ScrollView(this);
            settingsScroll.setFillViewport(true);
            settingsScroll.setClipToPadding(false);
            settingsScroll.setVerticalScrollBarEnabled(false);
            settingsScroll.addView(listPane, new ScrollView.LayoutParams(-1, -2));
            body.addView(settingsScroll, new LinearLayout.LayoutParams(-1, -1));
        } else {
            listPane = buildListPane();
            detailPane = buildDetailPane();
        if (tablet) {
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -1, 0.43f);
            left.setMarginEnd(dp(18));
            body.addView(listPane, left);
            body.addView(detailPane, new LinearLayout.LayoutParams(0, -1, 0.57f));
        } else {
            body.addView(listPane, new LinearLayout.LayoutParams(-1, -1));
            body.addView(detailPane, new LinearLayout.LayoutParams(-1, -1));
            detailPane.setVisibility(View.GONE);
        }
        }
        root.addView(buildBottomNav(), new LinearLayout.LayoutParams(-1, dp(68)));
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int horizontal = tablet ? 32 : compactPhone ? 14 : 20;
        bar.setPadding(dp(horizontal), dp(12), dp(horizontal), dp(10));
        TextView brand = text("단어포켓", compactPhone ? 22 : 25, INK, Typeface.BOLD);
        bar.addView(brand);
        bar.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));

        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            TextView importer = text(compactPhone ? "가져오기" : "가져오기", compactPhone ? 11 : 13, GREEN, Typeface.BOLD);
            importer.setGravity(Gravity.CENTER);
            importer.setPadding(dp(compactPhone ? 9 : 12), 0, dp(compactPhone ? 9 : 12), 0);
            importer.setBackground(roundRect(GREEN_SOFT, 13, 0, 0));
            importer.setOnClickListener(v -> filePicker.launch(new String[]{
                    "text/tab-separated-values", "text/csv", "text/plain",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            }));
            LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(-2, dp(40));
            importLp.setMarginEnd(dp(8));
            bar.addView(importer, importLp);
        }

        TextView add = text(compactPhone ? "추가" : "단어 추가", compactPhone ? 12 : 14, Color.WHITE, Typeface.BOLD);
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(compactPhone ? 12 : 15), 0, dp(compactPhone ? 12 : 15), 0);
        add.setBackground(roundRect(GREEN, 13, 0, 0));
        add.setOnClickListener(v -> showAddWordDialog());
        bar.addView(add, new LinearLayout.LayoutParams(-2, dp(40)));
        return bar;
    }

    private void importSelectedFile(Uri uri) {
        if (uri == null) return;
        Toast.makeText(this, "파일을 읽는 중이에요…", Toast.LENGTH_SHORT).show();
        importExecutor.execute(() -> {
            try {
                WordDatabase.ImportResult result = WordFileImporter.importUri(this, uri, database);
                runOnUiThread(() -> {
                    reload("");
                    Toast.makeText(this, result.imported + "개 저장 · " + result.skipped +
                            "개 중복 제외" + (result.invalid > 0 ? " · " + result.invalid + "개 오류" : ""),
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "가져오지 못했어요: " + readableError(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void exportProgress(Uri uri) {
        if (uri == null) return;
        importExecutor.execute(() -> {
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    getContentResolver().openOutputStream(uri, "wt"), StandardCharsets.UTF_8)) {
                int count = database.exportProgress(writer);
                runOnUiThread(() -> Toast.makeText(this,
                        count + "개의 학습 기록을 저장했어요.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "기록을 저장하지 못했어요: " + readableError(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void importProgress(Uri uri) {
        if (uri == null) return;
        Toast.makeText(this, "학습 기록을 확인하는 중이에요…", Toast.LENGTH_SHORT).show();
        importExecutor.execute(() -> {
            try (InputStreamReader reader = new InputStreamReader(
                    getContentResolver().openInputStream(uri), StandardCharsets.UTF_8)) {
                WordDatabase.ProgressResult result = database.importProgress(reader);
                runOnUiThread(() -> {
                    rebuildCurrentSection();
                    Toast.makeText(this, result.restored + "개 기록 복원" +
                                    (result.invalid > 0 ? " · " + result.invalid + "개 제외" : ""),
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "기록을 불러오지 못했어요: " + readableError(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String readableError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "파일 형식을 확인해 주세요." : current.getMessage();
    }

    private void showAddWordDialog() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), dp(8));
        String[] hints = {"단어 *", "품사", "뜻 *", "발음기호", "예문 1", "예문 1 해석",
                "예문 2", "예문 2 해석", "메모"};
        EditText[] inputs = new EditText[hints.length];
        for (int i = 0; i < hints.length; i++) {
            EditText input = new EditText(this);
            input.setHint(hints[i]); input.setTextSize(15); input.setTextColor(INK);
            input.setHintTextColor(MUTED); input.setSingleLine(i < 4);
            input.setMinLines(i >= 4 ? 2 : 1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(5);
            form.addView(input, lp); inputs[i] = input;
        }
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("새 단어 추가")
                .setView(scroll)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String wordText = inputs[0].getText().toString().trim();
            String meaningText = inputs[2].getText().toString().trim();
            if (wordText.isEmpty() || meaningText.isEmpty()) {
                Toast.makeText(this, "단어와 뜻은 꼭 입력해 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            Word word = new Word();
            word.word = wordText; word.partOfSpeech = inputs[1].getText().toString(); word.meaning = meaningText;
            word.pronunciation = inputs[3].getText().toString(); word.example1 = inputs[4].getText().toString();
            word.example1Meaning = inputs[5].getText().toString(); word.example2 = inputs[6].getText().toString();
            word.example2Meaning = inputs[7].getText().toString(); word.memo = inputs[8].getText().toString();
            long id = database.addWord(word);
            if (id == -1) Toast.makeText(this, "이미 같은 내용의 단어가 있어요.", Toast.LENGTH_SHORT).show();
            else { reload(""); Toast.makeText(this, "단어를 저장했어요.", Toast.LENGTH_SHORT).show(); dialog.dismiss(); }
        }));
        dialog.show();
    }

    private LinearLayout buildListPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        if (currentSection == SECTION_HOME) {
            pane.addView(buildSummaryCard(), new LinearLayout.LayoutParams(-1, -2));
        } else {
            String title = currentSection == SECTION_WORDBOOK ? "내 단어장" : "복습하기";
            String subtitle = currentSection == SECTION_WORDBOOK
                    ? database.countWords() + "개의 단어를 검색하고 관리해요."
                    : "외웠다고 표시한 " + database.countMastered() + "개 단어를 다시 확인해요.";
            pane.addView(buildSectionHeader(title, subtitle), new LinearLayout.LayoutParams(-1, dp(112)));
        }

        EditText search = null;
        if (currentSection != SECTION_HOME) {
            search = new EditText(this);
            search.setSingleLine(true);
            search.setTextSize(16);
            search.setHint("단어 또는 뜻 검색");
            search.setHintTextColor(Color.rgb(135, 145, 140));
            search.setTextColor(INK);
            search.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
            search.setCompoundDrawablePadding(dp(10));
            search.setPadding(dp(16), 0, dp(16), 0);
            search.setBackground(roundRect(Color.WHITE, 16, BORDER, 1));
            LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(52));
            searchLp.topMargin = dp(16);
            pane.addView(search, searchLp);
        }

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(2), dp(18), dp(2), dp(8));
        String listTitle = currentSection == SECTION_HOME ? "오늘의 단어"
                : currentSection == SECTION_WORDBOOK ? "전체 단어" : "외운 단어";
        titleRow.addView(text(listTitle, 19, INK, Typeface.BOLD));
        titleRow.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (currentSection == SECTION_HOME) {
            TextView quiz = text("시험 보기", 12, Color.WHITE, Typeface.BOLD);
            quiz.setGravity(Gravity.CENTER);
            quiz.setPadding(dp(12), dp(7), dp(12), dp(7));
            quiz.setBackground(roundRect(GREEN, 12, 0, 0));
            quiz.setOnClickListener(v -> startQuiz());
            LinearLayout.LayoutParams quizLp = lpMatchWrap();
            quizLp.setMarginEnd(dp(6));
            titleRow.addView(quiz, quizLp);
        }
        String listCount = currentSection == SECTION_HOME ? "＋ " + dailyGoal + "개"
                : currentSection == SECTION_WORDBOOK ? database.countWords() + "개" : database.countMastered() + "개";
        TextView countView = text(listCount, 13, GREEN, Typeface.BOLD);
        if (currentSection == SECTION_HOME) {
            countView.setGravity(Gravity.CENTER);
            countView.setPadding(dp(10), dp(7), dp(10), dp(7));
            countView.setBackground(roundRect(GREEN_SOFT, 12, 0, 0));
            countView.setOnClickListener(v -> addStudySet());
        }
        titleRow.addView(countView);
        pane.addView(titleRow);

        ListView list = new ListView(this);
        list.setVerticalScrollBarEnabled(false);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(10));
        adapter = new WordListAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> showWord(adapter.words.get(position)));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) { }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                if (currentSection != SECTION_HOME && hasMore && !loadingMore && total > 0 && first + visible >= total - 8) {
                    loadMore();
                }
            }
        });
        FrameLayout listArea = new FrameLayout(this);
        listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
        String emptyMessage = currentSection == SECTION_REVIEW
                ? "아직 외운 단어가 없어요.\n단어 상세에서 ‘이 단어 외웠어요’를 눌러보세요."
                : currentSection == SECTION_HOME && currentQuery.isEmpty()
                ? "오늘 학습을 모두 마쳤어요!\n더 하고 싶다면 위의 ‘＋ " + dailyGoal + "개’를 눌러보세요."
                : "검색 결과가 없어요.";
        TextView empty = text(emptyMessage, 15, MUTED, Typeface.NORMAL);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(5), 1f);
        listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        pane.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));

        if (search != null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { reload(s.toString()); }
                @Override public void afterTextChanged(Editable s) { }
            });
        }
        return pane;
    }

    private View buildSectionHeader(String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(22), dp(16), dp(22), dp(16));
        card.setBackground(roundRect(GREEN, 22, 0, 0));
        card.addView(text(title, 23, Color.WHITE, Typeface.BOLD));
        TextView sub = text(subtitle, 13, Color.rgb(210, 225, 218), Typeface.NORMAL);
        LinearLayout.LayoutParams lp = lpMatchWrap(); lp.topMargin = dp(7);
        card.addView(sub, lp);
        return card;
    }

    private View buildSummaryCard() {
        List<Word> today = database.searchToday("", todayGoal());
        int learnedCount = 0;
        for (Word word : today) if (word.mastery >= 100) learnedCount++;
        int goal = today.size();
        int percent = goal == 0 ? 0 : Math.round(learnedCount * 100f / goal);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setMinimumHeight(dp(tablet ? 150 : 160));
        card.setPadding(dp(22), dp(18), dp(22), dp(16));
        card.setBackground(roundRect(GREEN, 22, 0, 0));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text("오늘의 학습", 12, Color.rgb(245, 222, 164), Typeface.BOLD);
        badge.setPadding(dp(10), dp(5), dp(10), dp(5));
        badge.setBackground(roundRect(Color.rgb(66, 111, 94), 20, 0, 0));
        top.addView(badge);
        top.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
        String todayLabel = new SimpleDateFormat("M월 d일", Locale.KOREA).format(new Date());
        top.addView(text(todayLabel, 12, Color.rgb(206, 222, 215), Typeface.NORMAL));
        card.addView(top);
        TextView title = text("오늘도 단어 근육을\n가볍게 키워볼까요?", tablet ? 22 : 21, Color.WHITE, Typeface.BOLD);
        title.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, 0, 1);
        titleLp.topMargin = dp(9);
        card.addView(title, titleLp);
        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setProgress(percent);
        progress.setProgressTintList(ColorStateList.valueOf(GOLD));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(86, 126, 111)));
        progressRow.addView(progress, new LinearLayout.LayoutParams(0, dp(7), 1));
        TextView count = text(learnedCount + " / " + goal, 12, Color.WHITE, Typeface.BOLD);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-2, -2);
        countLp.setMarginStart(dp(12));
        progressRow.addView(count, countLp);
        card.addView(progressRow);
        return card;
    }

    private ScrollView buildDetailPane() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        detailContent = new LinearLayout(this);
        detailContent.setOrientation(LinearLayout.VERTICAL);
        detailContent.setPadding(tablet ? dp(26) : dp(4), dp(4), tablet ? dp(26) : dp(4), dp(28));
        scroll.addView(detailContent, new ScrollView.LayoutParams(-1, -2));
        renderEmptyDetail();
        return scroll;
    }

    private void renderEmptyDetail() {
        detailContent.removeAllViews();
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(30), dp(80), dp(30), dp(80));
        empty.setBackground(roundRect(Color.WHITE, 22, BORDER, 1));
        TextView icon = text("Aa", 36, GREEN, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundRect(GREEN_SOFT, 50, 0, 0));
        empty.addView(icon, new LinearLayout.LayoutParams(dp(78), dp(78)));
        TextView title = text("단어를 선택해 보세요", 20, INK, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = lpMatchWrap(); titleLp.topMargin = dp(20);
        empty.addView(title, titleLp);
        TextView sub = text("뜻, 긴 예문과 메모가\n이곳에 보기 좋게 표시됩니다.", 14, MUTED, Typeface.NORMAL);
        sub.setGravity(Gravity.CENTER); sub.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams subLp = lpMatchWrap(); subLp.topMargin = dp(8);
        empty.addView(sub, subLp);
        detailContent.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showWord(Word word) {
        selectedWord = word;
        showingDetail = true;
        if (!tablet) {
            listPane.setVisibility(View.GONE);
            detailPane.setVisibility(View.VISIBLE);
        }
        detailContent.removeAllViews();

        if (!tablet) {
            TextView back = text("단어 목록으로", 15, GREEN, Typeface.BOLD);
            back.setPadding(0, dp(8), 0, dp(14));
            back.setOnClickListener(v -> showList());
            detailContent.addView(back);
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setBackground(roundRect(Color.WHITE, 22, BORDER, 1));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.TOP);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView wordView = text(word.word, tablet ? 34 : 31, INK, Typeface.BOLD);
        words.addView(wordView);
        TextView pronunciation = text(word.pronunciation, 15, MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams pronLp = lpMatchWrap(); pronLp.topMargin = dp(5);
        words.addView(pronunciation, pronLp);
        heading.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView speaker = new ImageView(this);
        speaker.setImageResource(R.drawable.ic_volume);
        speaker.setImageTintList(ColorStateList.valueOf(GREEN));
        speaker.setPadding(dp(11), dp(11), dp(11), dp(11));
        speaker.setBackground(roundRect(GREEN_SOFT, 50, 0, 0));
        speaker.setContentDescription(word.word + " 발음 듣기");
        speaker.setOnClickListener(v -> speakWord(word));
        heading.addView(speaker, new LinearLayout.LayoutParams(dp(46), dp(46)));
        card.addView(heading);

        TextView pos = text(word.partOfSpeech, 12, GREEN, Typeface.BOLD);
        pos.setPadding(dp(10), dp(5), dp(10), dp(5));
        pos.setBackground(roundRect(GREEN_SOFT, 20, 0, 0));
        LinearLayout.LayoutParams posLp = lpMatchWrap(); posLp.topMargin = dp(18);
        card.addView(pos, posLp);
        TextView meaning = text(word.meaning, 21, INK, Typeface.BOLD);
        LinearLayout.LayoutParams meaningLp = lpMatchWrap(); meaningLp.topMargin = dp(11);
        card.addView(meaning, meaningLp);

        card.addView(divider(), dividerLp());
        addSection(card, "EXAMPLE 01", word.example1, word.example1Meaning);
        card.addView(divider(), dividerLp());
        addSection(card, "EXAMPLE 02", word.example2, word.example2Meaning);
        card.addView(divider(), dividerLp());
        addSection(card, "MEMO", word.memo, "");

        boolean reviewMode = currentSection == SECTION_REVIEW && word.mastery >= 100;
        if (reviewMode) {
            TextView learned = text("다시 학습하기", 15, Color.WHITE, Typeface.BOLD);
            learned.setGravity(Gravity.CENTER);
            learned.setBackground(roundRect(GREEN, 14, 0, 0));
            learned.setOnClickListener(v -> {
                word.mastery = 0;
                database.updateMastery(word.id, 0);
                Toast.makeText(this, "다시 학습할 단어로 되돌렸어요.", Toast.LENGTH_SHORT).show();
                rebuildCurrentSection();
            });
            LinearLayout.LayoutParams learnedLp = new LinearLayout.LayoutParams(-1, dp(50));
            learnedLp.topMargin = dp(18);
            card.addView(learned, learnedLp);
        }
        detailContent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        detailPane.post(() -> detailPane.smoothScrollTo(0, 0));
    }

    private void addSection(LinearLayout parent, String label, String main, String translation) {
        TextView l = text(label, 11, GREEN, Typeface.BOLD);
        parent.addView(l);
        TextView body = text(main, 16, INK, Typeface.NORMAL);
        body.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams bodyLp = lpMatchWrap(); bodyLp.topMargin = dp(9);
        parent.addView(body, bodyLp);
        if (translation != null && !translation.isEmpty()) {
            TextView ko = text(translation, 14, MUTED, Typeface.NORMAL);
            ko.setLineSpacing(dp(4), 1f);
            LinearLayout.LayoutParams koLp = lpMatchWrap(); koLp.topMargin = dp(7);
            parent.addView(ko, koLp);
        }
    }

    private void speakWord(Word word) {
        if (!ttsReady) {
            Toast.makeText(this, "영어 음성 엔진을 준비하는 중이거나 설치되어 있지 않아요.", Toast.LENGTH_SHORT).show();
            return;
        }
        textToSpeech.speak(word.word, TextToSpeech.QUEUE_FLUSH, null, "word-" + word.id);
    }

    private void initializeHandwritingRecognizers() {
        initializeHandwritingRecognizer("en-US", true);
        initializeHandwritingRecognizer("ko-KR", false);
    }

    private void initializeHandwritingRecognizer(String languageTag, boolean english) {
        try {
            DigitalInkRecognitionModelIdentifier identifier =
                    DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag);
            if (identifier == null) return;
            DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(identifier).build();
            DigitalInkRecognizer recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build());
            if (english) {
                englishHandwritingModel = model;
                englishHandwritingRecognizer = recognizer;
            } else {
                koreanHandwritingModel = model;
                koreanHandwritingRecognizer = recognizer;
            }
            RemoteModelManager manager = RemoteModelManager.getInstance();
            manager.isModelDownloaded(model)
                    .addOnSuccessListener(downloaded -> {
                        if (downloaded) {
                            setHandwritingModelReady(english);
                        } else {
                            manager.download(model, new DownloadConditions.Builder().build())
                                    .addOnSuccessListener(unused -> setHandwritingModelReady(english))
                                    .addOnFailureListener(error -> showHandwritingModelError(english));
                        }
                    })
                    .addOnFailureListener(error -> showHandwritingModelError(english));
        } catch (MlKitException error) {
            showHandwritingModelError(english);
        }
    }

    private void setHandwritingModelReady(boolean english) {
        if (english) englishHandwritingModelReady = true;
        else koreanHandwritingModelReady = true;
        updateHandwritingStatus();
    }

    private void showHandwritingModelError(boolean english) {
        if (english) englishHandwritingModelReady = false;
        else koreanHandwritingModelReady = false;
        updateHandwritingStatus();
    }

    private boolean isCurrentHandwritingModelReady() {
        return currentQuestion != null && (currentQuestion.askForWord
                ? englishHandwritingModelReady : koreanHandwritingModelReady);
    }

    private DigitalInkRecognizer currentHandwritingRecognizer() {
        return currentQuestion.askForWord
                ? englishHandwritingRecognizer : koreanHandwritingRecognizer;
    }

    private void updateHandwritingStatus() {
        if (handwritingStatus == null || currentQuestion == null) return;
        handwritingStatus.setText(isCurrentHandwritingModelReady()
                ? "펜이나 손가락으로 크게 써보세요."
                : (currentQuestion.askForWord ? "영어" : "한글") + " 손글씨 인식 모델을 준비하고 있어요.");
    }

    private void startQuiz() {
        List<Word> today = database.searchToday("", todayGoal());
        quizWords.clear();
        quizQueue.clear();
        for (Word word : today) {
            if (word.mastery >= 100) continue;
            quizWords.add(word);
            quizQueue.add(new QuizQuestion(word, false));
        }
        if (quizWords.isEmpty()) {
            Toast.makeText(this, "오늘 시험 볼 단어가 없어요.", Toast.LENGTH_SHORT).show();
            return;
        }
        quizMode = true;
        quizComplete = false;
        quizPhase = 1;
        quizPassed = 0;
        quizWrong = 0;
        showNextQuizQuestion();
    }

    private void showNextQuizQuestion() {
        if (quizQueue.isEmpty()) {
            if (quizPhase == 1) {
                startSecondQuizPhase();
                return;
            }
            finishQuiz();
            return;
        }
        currentQuestion = quizQueue.remove(0);
        quizAnswered = false;
        pendingHandwritingAnswer = null;
        View screen = buildQuizScreen();
        setContentView(screen);
        ViewCompat.requestApplyInsets(screen);
        if (!tablet) {
            quizAnswerInput.postDelayed(() -> {
                quizAnswerInput.requestFocus();
                InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                keyboard.showSoftInput(quizAnswerInput, InputMethodManager.SHOW_IMPLICIT);
            }, 180);
        }
    }

    private void startSecondQuizPhase() {
        quizPhase = 2;
        quizPassed = 0;
        for (Word word : quizWords) quizQueue.add(new QuizQuestion(word, true));
        Toast.makeText(this, "1차 통과! 이제 영어 단어를 맞혀보세요.", Toast.LENGTH_LONG).show();
        showNextQuizQuestion();
    }

    private View buildQuizScreen() {
        LinearLayout root = quizRoot();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(compactPhone ? 16 : 24), dp(14), dp(compactPhone ? 16 : 24), dp(12));
        top.addView(text("단어포켓 시험", compactPhone ? 21 : 24, INK, Typeface.BOLD));
        top.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView cancel = text("시험 끝내기", 13, MUTED, Typeface.BOLD);
        cancel.setPadding(dp(10), dp(8), dp(10), dp(8));
        cancel.setOnClickListener(v -> confirmExitQuiz());
        top.addView(cancel);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = tablet ? 150 : compactPhone ? 16 : 22;
        content.setPadding(dp(side), dp(12), dp(side), dp(28));

        int remaining = quizQueue.size() + 1;
        String phaseLabel = quizPhase == 1 ? "1차 · 한글 뜻 맞히기" : "2차 · 영어 단어 맞히기";
        TextView progress = text(phaseLabel + "  ·  " + remaining + "개 남음",
                13, GREEN, Typeface.BOLD);
        content.addView(progress);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(Math.max(1, quizWords.size()));
        bar.setProgress(quizPassed);
        bar.setProgressTintList(ColorStateList.valueOf(GREEN));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(GREEN_SOFT));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(7));
        barLp.topMargin = dp(10);
        barLp.bottomMargin = dp(18);
        content.addView(bar, barLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(compactPhone ? 20 : 28), dp(24), dp(compactPhone ? 20 : 28), dp(26));
        card.setBackground(roundRect(Color.WHITE, 22, BORDER, 1));

        String direction = currentQuestion.askForWord
                ? (tablet ? "뜻을 보고 영어로 손글씨 쓰기" : "뜻을 보고 영어 입력")
                : (tablet ? "영어를 보고 한글 뜻 손글씨 쓰기" : "영어를 보고 뜻 입력");
        TextView badge = text(direction, 12, GREEN, Typeface.BOLD);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(roundRect(GREEN_SOFT, 18, 0, 0));
        card.addView(badge, lpMatchWrap());

        String prompt = currentQuestion.askForWord
                ? currentQuestion.word.meaning : currentQuestion.word.word;
        TextView question = text(prompt, currentQuestion.askForWord ? 23 : 31, INK, Typeface.BOLD);
        question.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams questionLp = new LinearLayout.LayoutParams(-1, -2);
        questionLp.topMargin = dp(22);
        card.addView(question, questionLp);

        String helper = currentQuestion.askForWord
                ? currentQuestion.word.partOfSpeech
                : currentQuestion.word.partOfSpeech + (currentQuestion.word.pronunciation.isEmpty()
                ? "" : "  " + currentQuestion.word.pronunciation);
        TextView helperView = text(helper, 13, MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams helperLp = new LinearLayout.LayoutParams(-1, -2);
        helperLp.topMargin = dp(7);
        card.addView(helperView, helperLp);

        quizAnswerInput = null;
        handwritingPad = null;
        if (tablet) {
            handwritingPad = new HandwritingPad(this);
            LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(-1, dp(330));
            padLp.topMargin = dp(22);
            card.addView(handwritingPad, padLp);

            LinearLayout tools = new LinearLayout(this);
            tools.setGravity(Gravity.CENTER_VERTICAL);
            handwritingStatus = text(isCurrentHandwritingModelReady()
                    ? "펜이나 손가락으로 크게 써보세요."
                    : (currentQuestion.askForWord ? "영어" : "한글") +
                    " 손글씨 인식 모델을 준비하고 있어요.", 13, MUTED, Typeface.NORMAL);
            tools.addView(handwritingStatus, new LinearLayout.LayoutParams(0, -2, 1f));
            handwritingClear = actionButton("모두 지우기", false);
            handwritingClear.setOnClickListener(v -> resetHandwritingDraft());
            tools.addView(handwritingClear, new LinearLayout.LayoutParams(dp(128), dp(46)));
            LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(-1, -2);
            toolsLp.topMargin = dp(10);
            card.addView(tools, toolsLp);
        } else {
            quizAnswerInput = new EditText(this);
            quizAnswerInput.setSingleLine(true);
            quizAnswerInput.setTextSize(17);
            quizAnswerInput.setTextColor(INK);
            quizAnswerInput.setHint(currentQuestion.askForWord ? "영어 단어를 입력하세요" : "뜻을 입력하세요");
            quizAnswerInput.setHintTextColor(Color.rgb(137, 147, 142));
            quizAnswerInput.setPadding(dp(15), 0, dp(15), 0);
            quizAnswerInput.setBackground(roundRect(Color.WHITE, 14, GREEN, 1));
            quizAnswerInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            quizAnswerInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
            LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, dp(54));
            inputLp.topMargin = dp(24);
            card.addView(quizAnswerInput, inputLp);
        }

        quizFeedback = text("", 14, INK, Typeface.BOLD);
        quizFeedback.setLineSpacing(dp(4), 1f);
        quizFeedback.setPadding(dp(14), dp(13), dp(14), dp(13));
        quizFeedback.setVisibility(View.GONE);
        LinearLayout.LayoutParams feedbackLp = new LinearLayout.LayoutParams(-1, -2);
        feedbackLp.topMargin = dp(12);
        card.addView(quizFeedback, feedbackLp);

        quizAction = actionButton(tablet ? "손글씨 확인하기" : "정답 확인", true);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-1, dp(52));
        actionLp.topMargin = dp(16);
        card.addView(quizAction, actionLp);
        quizAction.setOnClickListener(v -> {
            if (tablet) submitHandwritingAnswer(); else checkQuizAnswer();
        });
        if (quizAnswerInput != null) {
            quizAnswerInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    checkQuizAnswer();
                    return true;
                }
                return false;
            });
        }

        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        TextView rule = text("1차 한글 뜻과 2차 영어 단어를 모두 맞힌 경우에만 외운 단어로 저장돼요.",
                13, MUTED, Typeface.NORMAL);
        rule.setGravity(Gravity.CENTER);
        rule.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(-1, -2);
        ruleLp.topMargin = dp(16);
        content.addView(rule, ruleLp);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private LinearLayout quizRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(systemInsetLeft, systemInsetTop, systemInsetRight, systemInsetBottom);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            systemInsetLeft = bars.left;
            systemInsetTop = bars.top;
            systemInsetRight = bars.right;
            systemInsetBottom = bars.bottom;
            view.setPadding(systemInsetLeft, systemInsetTop, systemInsetRight, systemInsetBottom);
            return insets;
        });
        return root;
    }

    private void checkQuizAnswer() {
        if (quizAnswered) {
            showNextQuizQuestion();
            return;
        }
        String answer = quizAnswerInput.getText().toString().trim();
        if (answer.isEmpty()) {
            Toast.makeText(this, "답을 입력해 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean correct = currentQuestion.askForWord
                ? normalizeEnglish(answer).equals(normalizeEnglish(currentQuestion.word.word))
                : matchesMeaning(answer, currentQuestion.word.meaning);
        applyQuizAnswer(correct, null);
    }

    private void submitHandwritingAnswer() {
        if (quizAnswered) {
            showNextQuizQuestion();
            return;
        }
        if (pendingHandwritingAnswer != null) {
            submitReviewedHandwritingAnswer();
            return;
        }
        DigitalInkRecognizer recognizer = currentHandwritingRecognizer();
        if (!isCurrentHandwritingModelReady() || recognizer == null) {
            Toast.makeText(this, (currentQuestion.askForWord ? "영어" : "한글") +
                    " 손글씨 인식 모델을 준비하는 중이에요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (handwritingPad == null || !handwritingPad.hasInk()) {
            Toast.makeText(this, "연습장에 답을 써 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        quizAction.setEnabled(false);
        quizAction.setText("손글씨 읽는 중…");
        recognizer.recognize(handwritingPad.getInk())
                .addOnSuccessListener(result -> {
                    String recognized = result.getCandidates().isEmpty()
                            ? "" : result.getCandidates().get(0).getText();
                    if (recognized.isEmpty()) {
                        quizAction.setEnabled(true);
                        quizAction.setText("손글씨 확인하기");
                        Toast.makeText(this, "글씨를 읽지 못했어요. 지우고 다시 써 주세요.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pendingHandwritingAnswer = recognized;
                    handwritingPad.setEnabled(false);
                    quizFeedback.setText("이렇게 읽었어요\n" + recognized +
                            "\n\n다르면 ‘다시 쓰기’를 눌러 고쳐 주세요.");
                    quizFeedback.setTextColor(INK);
                    quizFeedback.setBackground(roundRect(GREEN_SOFT, 13, 0, 0));
                    quizFeedback.setVisibility(View.VISIBLE);
                    handwritingClear.setText("다시 쓰기");
                    quizAction.setEnabled(true);
                    quizAction.setText("제출하고 다음 문제");
                })
                .addOnFailureListener(error -> {
                    quizAction.setEnabled(true);
                    quizAction.setText("손글씨 확인하기");
                    Toast.makeText(this, "손글씨를 읽지 못했어요. 다시 시도해 주세요.",
                            Toast.LENGTH_SHORT).show();
                });
    }

    private void resetHandwritingDraft() {
        if (quizAnswered || handwritingPad == null) return;
        pendingHandwritingAnswer = null;
        handwritingPad.setEnabled(true);
        handwritingPad.clear();
        if (quizFeedback != null) quizFeedback.setVisibility(View.GONE);
        if (handwritingClear != null) handwritingClear.setText("모두 지우기");
        if (quizAction != null) {
            quizAction.setEnabled(true);
            quizAction.setText("손글씨 확인하기");
        }
    }

    private void submitReviewedHandwritingAnswer() {
        String answer = pendingHandwritingAnswer;
        boolean correct = currentQuestion.askForWord
                ? normalizeEnglish(answer).equals(normalizeEnglish(currentQuestion.word.word))
                : matchesMeaning(answer, currentQuestion.word.meaning);
        String expected = currentQuestion.askForWord
                ? currentQuestion.word.word : currentQuestion.word.meaning;
        if (correct) {
            quizPassed++;
            Toast.makeText(this, "정답이에요!  " + expected, Toast.LENGTH_SHORT).show();
        } else {
            quizWrong++;
            quizQueue.add(currentQuestion);
            Toast.makeText(this, "다시 나올 문제예요. 정답: " + expected, Toast.LENGTH_LONG).show();
        }
        pendingHandwritingAnswer = null;
        showNextQuizQuestion();
    }

    private void applyQuizAnswer(boolean correct, String recognized) {
        quizAnswered = true;
        if (quizAnswerInput != null) quizAnswerInput.setEnabled(false);
        if (handwritingPad != null) handwritingPad.setEnabled(false);
        String expected = currentQuestion.askForWord
                ? currentQuestion.word.word : currentQuestion.word.meaning;
        String recognitionLine = recognized == null ? "" : "\n인식한 답: " + recognized;
        if (correct) {
            quizPassed++;
            quizFeedback.setText("정답이에요!" + recognitionLine + "\n정답: " + expected);
            quizFeedback.setTextColor(GREEN);
            quizFeedback.setBackground(roundRect(GREEN_SOFT, 13, 0, 0));
        } else {
            quizWrong++;
            quizQueue.add(currentQuestion);
            quizFeedback.setText("아쉬워요. 이 문제는 다시 나와요." + recognitionLine + "\n정답: " + expected);
            quizFeedback.setTextColor(ERROR);
            quizFeedback.setBackground(roundRect(Color.rgb(249, 232, 230), 13, 0, 0));
        }
        quizFeedback.setVisibility(View.VISIBLE);
        quizAction.setText(quizQueue.isEmpty()
                ? (quizPhase == 1 ? "2차 영어 시험 시작" : "시험 완료하기")
                : "다음 문제");
        quizAction.setEnabled(true);
        hideKeyboard(quizAnswerInput);
    }

    private boolean matchesMeaning(String answer, String expected) {
        String normalizedAnswer = normalizeMeaning(answer);
        if (normalizedAnswer.isEmpty()) return false;
        String normalizedExpected = normalizeMeaning(expected);
        if (normalizedExpected.equals(normalizedAnswer)) return true;
        String[] choices = expected.split("[,;/·]|\\(|\\)");
        for (String choice : choices) {
            String normalizedChoice = normalizeMeaning(choice);
            if (!normalizedChoice.isEmpty() &&
                    (normalizedChoice.equals(normalizedAnswer) ||
                            (normalizedAnswer.length() >= 2 &&
                                    (normalizedChoice.contains(normalizedAnswer)
                                            || normalizedAnswer.contains(normalizedChoice))))) {
                return true;
            }
        }
        return normalizedAnswer.length() >= 2 && normalizedExpected.contains(normalizedAnswer);
    }

    private String normalizeEnglish(String value) {
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private String normalizeMeaning(String value) {
        return value.toLowerCase(Locale.KOREA).replaceAll("[\\s\\p{Punct}·~]", "");
    }

    private void finishQuiz() {
        database.markMastered(quizWords);
        quizComplete = true;
        currentQuestion = null;
        hideKeyboard(null);
        View screen = buildQuizCompleteScreen();
        setContentView(screen);
        ViewCompat.requestApplyInsets(screen);
    }

    private View buildQuizCompleteScreen() {
        LinearLayout root = quizRoot();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int side = tablet ? 180 : compactPhone ? 20 : 28;
        content.setPadding(dp(side), dp(30), dp(side), dp(30));
        TextView mark = text("100", 34, Color.WHITE, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(roundRect(GREEN, 50, 0, 0));
        content.addView(mark, new LinearLayout.LayoutParams(dp(86), dp(86)));
        TextView title = text("오늘의 시험을 통과했어요!", 25, INK, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = lpMatchWrap();
        titleLp.topMargin = dp(24);
        content.addView(title, titleLp);
        TextView summary = text(quizWords.size() + "개 단어의 한글·영어 시험 완료 · 오답 " + quizWrong + "회\n" +
                "두 단계를 모두 통과해 외운 단어로 저장했어요.", 15, MUTED, Typeface.NORMAL);
        summary.setGravity(Gravity.CENTER);
        summary.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(-1, -2);
        summaryLp.topMargin = dp(12);
        content.addView(summary, summaryLp);
        TextView home = actionButton("홈으로 돌아가기", true);
        home.setOnClickListener(v -> exitQuiz());
        LinearLayout.LayoutParams homeLp = new LinearLayout.LayoutParams(-1, dp(54));
        homeLp.topMargin = dp(28);
        content.addView(home, homeLp);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private void confirmExitQuiz() {
        if (quizComplete) {
            exitQuiz();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("시험을 끝낼까요?")
                .setMessage("아직 통과하지 않은 문제는 외운 것으로 저장되지 않아요.")
                .setNegativeButton("계속 풀기", null)
                .setPositiveButton("시험 끝내기", (dialog, which) -> exitQuiz())
                .show();
    }

    private void exitQuiz() {
        quizMode = false;
        quizComplete = false;
        quizAnswered = false;
        quizQueue.clear();
        quizWords.clear();
        currentQuestion = null;
        currentSection = SECTION_HOME;
        rebuildCurrentSection();
    }

    private void hideKeyboard(View view) {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View target = view != null ? view : getCurrentFocus();
        if (target != null) keyboard.hideSoftInputFromWindow(target.getWindowToken(), 0);
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(tablet ? dp(180) : dp(6), dp(5), tablet ? dp(180) : dp(6), dp(5));
        nav.setBackgroundColor(Color.WHITE);
        nav.addView(navItem(R.drawable.ic_nav_home, "홈", SECTION_HOME), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(R.drawable.ic_nav_book, "단어장", SECTION_WORDBOOK), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(R.drawable.ic_nav_review, "복습", SECTION_REVIEW), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navItem(R.drawable.ic_nav_settings, "설정", SECTION_SETTINGS), new LinearLayout.LayoutParams(0, -1, 1));
        return nav;
    }

    private View navItem(int iconRes, String label, int section) {
        boolean active = currentSection == section;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setContentDescription(label);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(active ? GREEN : MUTED));
        item.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView name = text(label, 11, active ? GREEN : MUTED, active ? Typeface.BOLD : Typeface.NORMAL);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = lpMatchWrap(); lp.topMargin = dp(4);
        item.addView(name, lp);
        item.setOnClickListener(v -> switchSection(section));
        return item;
    }

    private void reload(String query) {
        if (adapter == null) return;
        currentQuery = query == null ? "" : query;
        currentOffset = 0;
        if (currentSection == SECTION_HOME) {
            List<Word> todayWords = database.searchToday(currentQuery, todayGoal());
            adapter.words = new ArrayList<>();
            for (Word word : todayWords) if (word.mastery < 100) adapter.words.add(word);
            hasMore = false;
        } else {
            adapter.words = database.search(currentQuery, PAGE_SIZE, 0, currentSection == SECTION_REVIEW);
            currentOffset = adapter.words.size();
            hasMore = adapter.words.size() == PAGE_SIZE;
        }
        adapter.notifyDataSetChanged();
        if (tablet && selectedWord == null && !adapter.words.isEmpty()) showWord(adapter.words.get(0));
    }

    private void loadMore() {
        if (adapter == null || loadingMore || !hasMore) return;
        loadingMore = true;
        List<Word> next = database.search(currentQuery, PAGE_SIZE, currentOffset, currentSection == SECTION_REVIEW);
        adapter.words.addAll(next);
        currentOffset += next.size();
        hasMore = next.size() == PAGE_SIZE;
        loadingMore = false;
        adapter.notifyDataSetChanged();
    }

    private void switchSection(int section) {
        if (currentSection == section && !showingDetail) return;
        currentSection = section;
        rebuildCurrentSection();
    }

    private void rebuildCurrentSection() {
        selectedWord = null;
        showingDetail = false;
        currentQuery = "";
        currentOffset = 0;
        View screen = buildScreen();
        setContentView(screen);
        ViewCompat.requestApplyInsets(screen);
        if (currentSection != SECTION_SETTINGS) reload("");
    }

    private LinearLayout buildSettingsPane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.addView(buildSectionHeader("설정", "앱과 저장된 학습 데이터를 확인해요."),
                new LinearLayout.LayoutParams(-1, dp(112)));
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(tablet ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        stats.setPadding(0, dp(16), 0, 0);
        stats.addView(settingCard("저장된 단어", database.countWords() + "개"),
                new LinearLayout.LayoutParams(tablet ? 0 : -1, dp(92), tablet ? 1f : 0f));
        LinearLayout.LayoutParams learnedLp = new LinearLayout.LayoutParams(tablet ? 0 : -1, dp(92), tablet ? 1f : 0f);
        if (tablet) learnedLp.setMarginStart(dp(12)); else learnedLp.topMargin = dp(10);
        stats.addView(settingCard("외운 단어", database.countMastered() + "개"), learnedLp);
        pane.addView(stats);

        TextView goalTitle = text("하루 학습량", 18, INK, Typeface.BOLD);
        LinearLayout.LayoutParams goalTitleLp = lpMatchWrap(); goalTitleLp.topMargin = dp(26); goalTitleLp.bottomMargin = dp(9);
        pane.addView(goalTitle, goalTitleLp);
        pane.addView(buildGoalSelector(), new LinearLayout.LayoutParams(-1, dp(52)));

        TextView backupTitle = text("학습 기록 백업", 18, INK, Typeface.BOLD);
        LinearLayout.LayoutParams backupTitleLp = lpMatchWrap();
        backupTitleLp.topMargin = dp(28);
        backupTitleLp.bottomMargin = dp(9);
        pane.addView(backupTitle, backupTitleLp);
        TextView backupGuide = text("앱을 삭제하거나 다른 기기로 옮기기 전에 기록을 파일로 저장하세요.", 13, MUTED, Typeface.NORMAL);
        backupGuide.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams backupGuideLp = new LinearLayout.LayoutParams(-1, -2);
        backupGuideLp.bottomMargin = dp(12);
        pane.addView(backupGuide, backupGuideLp);
        LinearLayout backupActions = new LinearLayout(this);
        backupActions.setGravity(Gravity.CENTER_VERTICAL);
        TextView export = actionButton("기록 내보내기", true);
        export.setOnClickListener(v -> {
            String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            progressExporter.launch("word_pocket-progress-" + date + ".tsv");
        });
        backupActions.addView(export, new LinearLayout.LayoutParams(0, dp(50), 1f));
        TextView restore = actionButton("기록 불러오기", false);
        restore.setOnClickListener(v -> progressImporter.launch(new String[]{
                "text/tab-separated-values", "text/plain", "application/octet-stream"
        }));
        LinearLayout.LayoutParams restoreLp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        restoreLp.setMarginStart(dp(8));
        backupActions.addView(restore, restoreLp);
        pane.addView(backupActions, new LinearLayout.LayoutParams(-1, -2));

        TextView guide = text("단어 파일 가져오기", 18, INK, Typeface.BOLD);
        LinearLayout.LayoutParams guideLp = lpMatchWrap(); guideLp.topMargin = dp(28); guideLp.bottomMargin = dp(8);
        pane.addView(guide, guideLp);
        TextView description = text("디버그 앱에서는 상단의 가져오기 버튼으로 XLSX, CSV, TSV, TXT 파일을 불러올 수 있어요. 첫 번째 행은 헤더로 자동 제외됩니다.",
                14, MUTED, Typeface.NORMAL);
        description.setLineSpacing(dp(4), 1f);
        description.setPadding(dp(18), dp(16), dp(18), dp(16));
        description.setBackground(roundRect(Color.WHITE, 16, BORDER, 1));
        pane.addView(description, new LinearLayout.LayoutParams(-1, -2));
        Space bottomSpace = new Space(this);
        pane.addView(bottomSpace, new LinearLayout.LayoutParams(1, dp(28)));
        return pane;
    }

    private TextView actionButton(String label, boolean primary) {
        TextView button = text(label, 14, primary ? Color.WHITE : GREEN, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(roundRect(primary ? GREEN : Color.WHITE, 14, BORDER, primary ? 0 : 1));
        return button;
    }

    private View buildGoalSelector() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int[] goals = {10, 20, 30, 50, 100};
        for (int goal : goals) {
            boolean selected = dailyGoal == goal;
            TextView option = text(String.valueOf(goal), 14, selected ? Color.WHITE : GREEN, Typeface.BOLD);
            option.setGravity(Gravity.CENTER);
            option.setBackground(roundRect(selected ? GREEN : Color.WHITE, 13, BORDER, selected ? 0 : 1));
            option.setOnClickListener(v -> setDailyGoal(goal));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (goal != 10) lp.setMarginStart(dp(7));
            row.addView(option, lp);
        }
        return row;
    }

    private void showDailyGoalDialog() {
        int[] goals = {10, 20, 30, 50, 100};
        String[] labels = {"10개", "20개", "30개", "50개", "100개"};
        int checked = 0;
        for (int i = 0; i < goals.length; i++) if (goals[i] == dailyGoal) checked = i;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("하루 학습량")
                .setSingleChoiceItems(labels, checked, null)
                .setNegativeButton("취소", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            setDailyGoal(goals[position]);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void setDailyGoal(int goal) {
        dailyGoal = Math.max(10, Math.min(100, goal));
        todayExtraGoal = 0;
        getSharedPreferences("wordly_settings", MODE_PRIVATE).edit()
                .putInt("daily_goal", dailyGoal)
                .putString("extra_goal_date", todayKey())
                .putInt("extra_goal_count", 0)
                .apply();
        Toast.makeText(this, "하루 학습량을 " + dailyGoal + "개로 바꿨어요.", Toast.LENGTH_SHORT).show();
        rebuildCurrentSection();
    }

    private void addStudySet() {
        int maximum = Math.min(1000, database.countStudyCandidatesToday());
        int completedToday = database.countMasteredToday();
        int nextGoal = Math.min(maximum, Math.max(todayGoal(), completedToday) + dailyGoal);
        if (nextGoal <= todayGoal()) {
            Toast.makeText(this, "오늘 추가할 수 있는 단어를 모두 불러왔어요.", Toast.LENGTH_SHORT).show();
            return;
        }
        todayExtraGoal = Math.max(0, nextGoal - dailyGoal);
        getSharedPreferences("wordly_settings", MODE_PRIVATE).edit()
                .putString("extra_goal_date", todayKey())
                .putInt("extra_goal_count", todayExtraGoal)
                .apply();
        Toast.makeText(this, "한 세트를 더했어요. 오늘은 " + nextGoal + "개를 학습해요.",
                Toast.LENGTH_SHORT).show();
        rebuildCurrentSection();
    }

    private int todayGoal() {
        SharedPreferences settings = getSharedPreferences("wordly_settings", MODE_PRIVATE);
        if (!todayKey().equals(settings.getString("extra_goal_date", ""))) {
            todayExtraGoal = 0;
            settings.edit()
                    .putString("extra_goal_date", todayKey())
                    .putInt("extra_goal_count", 0)
                    .apply();
        }
        int maximum = Math.min(1000, database.countStudyCandidatesToday());
        return Math.min(maximum, dailyGoal + Math.max(0, todayExtraGoal));
    }

    private String todayKey() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private View settingCard(String label, String value) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(12), dp(18), dp(12));
        card.setBackground(roundRect(Color.WHITE, 16, BORDER, 1));
        card.addView(text(label, 13, MUTED, Typeface.NORMAL));
        TextView amount = text(value, 22, GREEN, Typeface.BOLD);
        LinearLayout.LayoutParams lp = lpMatchWrap(); lp.topMargin = dp(3);
        card.addView(amount, lp);
        return card;
    }

    private void showList() {
        showingDetail = false;
        selectedWord = null;
        listPane.setVisibility(View.VISIBLE);
        detailPane.setVisibility(tablet ? View.VISIBLE : View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(listPane.getWindowToken(), 0);
    }

    private final class HandwritingPad extends View {
        private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Path> paths = new ArrayList<>();
        private Path activePath;
        private Ink.Builder inkBuilder = Ink.builder();
        private Ink.Stroke.Builder strokeBuilder;
        private boolean containsInk;

        HandwritingPad(Context context) {
            super(context);
            inkPaint.setColor(INK);
            inkPaint.setStyle(Paint.Style.STROKE);
            inkPaint.setStrokeWidth(dp(4));
            inkPaint.setStrokeCap(Paint.Cap.ROUND);
            inkPaint.setStrokeJoin(Paint.Join.ROUND);
            linePaint.setColor(Color.rgb(224, 229, 223));
            linePaint.setStrokeWidth(dp(1));
            hintPaint.setColor(Color.rgb(155, 165, 160));
            hintPaint.setTextSize(dp(17));
            hintPaint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            setBackground(roundRect(Color.WHITE, 18, GREEN, 1));
            setContentDescription(currentQuestion != null && currentQuestion.askForWord
                    ? "영어 답을 손으로 쓰는 연습장" : "한글 뜻을 손으로 쓰는 연습장");
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int spacing = dp(72);
            for (int y = spacing; y < getHeight(); y += spacing) {
                canvas.drawLine(dp(20), y, getWidth() - dp(20), y, linePaint);
            }
            if (!containsInk) {
                String hint = currentQuestion != null && currentQuestion.askForWord
                        ? "여기에 영어 단어를 크게 써보세요"
                        : "여기에 한글 뜻을 크게 써보세요";
                canvas.drawText(hint, dp(24), dp(42), hintPaint);
            }
            for (Path path : paths) canvas.drawPath(path, inkPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            float x = event.getX();
            float y = event.getY();
            long time = event.getEventTime();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    activePath = new Path();
                    activePath.moveTo(x, y);
                    paths.add(activePath);
                    strokeBuilder = Ink.Stroke.builder();
                    strokeBuilder.addPoint(Ink.Point.create(x, y, time));
                    containsInk = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (activePath == null || strokeBuilder == null) return false;
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        float historicalX = event.getHistoricalX(i);
                        float historicalY = event.getHistoricalY(i);
                        activePath.lineTo(historicalX, historicalY);
                        strokeBuilder.addPoint(Ink.Point.create(
                                historicalX, historicalY, event.getHistoricalEventTime(i)));
                    }
                    activePath.lineTo(x, y);
                    strokeBuilder.addPoint(Ink.Point.create(x, y, time));
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (activePath != null && strokeBuilder != null) {
                        activePath.lineTo(x, y);
                        strokeBuilder.addPoint(Ink.Point.create(x, y, time));
                        inkBuilder.addStroke(strokeBuilder.build());
                    }
                    activePath = null;
                    strokeBuilder = null;
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        boolean hasInk() {
            return containsInk && strokeBuilder == null;
        }

        Ink getInk() {
            return inkBuilder.build();
        }

        void clear() {
            paths.clear();
            activePath = null;
            strokeBuilder = null;
            inkBuilder = Ink.builder();
            containsInk = false;
            invalidate();
        }
    }

    private final class WordListAdapter extends BaseAdapter {
        List<Word> words = new ArrayList<>();
        @Override public int getCount() { return words.size(); }
        @Override public Word getItem(int position) { return words.get(position); }
        @Override public long getItemId(int position) { return words.get(position).id; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Word word = getItem(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(74));
            row.setPadding(dp(14), dp(11), dp(12), dp(11));
            row.setBackground(roundRect(Color.WHITE, 17, BORDER, 1));

            TextView initial = text(word.word.substring(0, 1).toUpperCase(), 17, GREEN, Typeface.BOLD);
            initial.setGravity(Gravity.CENTER);
            initial.setBackground(roundRect(GREEN_SOFT, 14, 0, 0));
            row.addView(initial, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout copy = new LinearLayout(MainActivity.this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setPadding(dp(13), 0, dp(8), 0);
            LinearLayout first = new LinearLayout(MainActivity.this);
            first.setGravity(Gravity.CENTER_VERTICAL);
            TextView wordName = text(word.word, 17, INK, Typeface.BOLD);
            wordName.setSingleLine(true);
            wordName.setEllipsize(TextUtils.TruncateAt.END);
            first.addView(wordName, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView pos = text(word.partOfSpeech, 11, GREEN, Typeface.NORMAL);
            pos.setSingleLine(true);
            pos.setPadding(dp(7), 0, 0, 0);
            first.addView(pos);
            copy.addView(first);
            TextView meaning = text(word.meaning, 13, MUTED, Typeface.NORMAL);
            meaning.setMaxLines(1);
            LinearLayout.LayoutParams meaningLp = lpMatchWrap(); meaningLp.topMargin = dp(4);
            copy.addView(meaning, meaningLp);
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

            FrameLayout holder = new FrameLayout(MainActivity.this);
            holder.setPadding(0, dp(4), 0, dp(4));
            holder.addView(row, new FrameLayout.LayoutParams(-1, -1));
            holder.setMinimumHeight(dp(82));
            holder.setLayoutParams(new ListView.LayoutParams(-1, -2));
            return holder;
        }
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private View divider() { View v = new View(this); v.setBackgroundColor(BORDER); return v; }
    private LinearLayout.LayoutParams dividerLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(22); lp.bottomMargin = dp(22); return lp;
    }
    private LinearLayout.LayoutParams lpMatchWrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        if (englishHandwritingRecognizer != null) englishHandwritingRecognizer.close();
        if (koreanHandwritingRecognizer != null) koreanHandwritingRecognizer.close();
        importExecutor.shutdown();
        database.close();
        super.onDestroy();
    }
}
