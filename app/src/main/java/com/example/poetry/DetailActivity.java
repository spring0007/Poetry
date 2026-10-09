package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.example.poetry.data.Callback;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ActivityDetailBinding;
import com.example.poetry.media.Speaker;
import com.example.poetry.ui.PinyinView;
import com.example.poetry.ui.Skin;
import com.example.poetry.ui.VoiceSheet;
import com.example.poetry.util.Pinyin;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 作品详情页：顶部标题栏 + 正文（横排 / 竖排，可注音）+ 译文 / 注释 / 赏析 + 底部朗读条。
 * <p>
 * 竖屏下正文与各级标题一律居中排版；正文字号只有一个来源（text_xl × 缩放），
 * 不再出现样式与代码两套尺寸导致的「预览与实际不符」。
 *
 * <p>正文滚到顶 / 底之后继续同方向拖，就翻到上一篇 / 下一篇。「下一篇」的含义跟着入口走：
 * 书架进来沿书架顺序翻，分类进来沿该分类翻，搜索进来沿搜索结果翻，其余入口全库随机
 * （见 {@link #openInList}）。到两头就停住并提示，不循环。
 */
public class DetailActivity extends AppCompatActivity {

    private static final String EXTRA_POEM = "extra_poem";
    /** 来源列表各首诗的引用（uid 优先，无 uid 时是数字 id），按展示顺序 */
    private static final String EXTRA_QUEUE = "extra_queue";
    /** 当前这首在 {@link #EXTRA_QUEUE} 里的下标 */
    private static final String EXTRA_INDEX = "extra_index";
    /** 进入时到底带没带来源列表。重建后要靠它区分「列表到头」和「随机还能再抽」 */
    private static final String EXTRA_LIST_MODE = "extra_list_mode";

    /**
     * 平仄展示总开关。当前阶段用不到平仄，置 false 即整块屏蔽：
     * 详情页不再展示平仄行，相关请求与版面一并停用。
     * 数据链路与渲染逻辑（{@link #prettyStrain}）原样保留，改回 true 即恢复。
     */
    private static final boolean STRAIN_ENABLED = false;

    /** 越界拖多远才算翻页：太短会把「用力滚到底」误判成翻页 */
    private static final int SWIPE_TRIGGER_DP = 72;

    private ActivityDetailBinding binding;
    private PoetryRepository repository;
    private UserStore store;

    private Poem poem;
    private boolean verticalMode;
    private boolean pinyinShown;
    private String fontFamily = "serif";
    private float fontScale = 1.0f;
    private boolean playing;
    /** 循环播放：一首读完自动重播本首 */
    private boolean loopPlay;

    /**
     * 翻页链条。列表模式下是进入时的引用快照；随机模式下从当前这首开始，每往下翻一首就
     * 追加一个引用 —— 于是「随机」也天然有了会话内的回退历史（下滑能退回刚才抽到的那几首）。
     *
     * <p>存 {@link Poem#getLookupRef()} 而不是 {@code id}：详情页现在优先走后端，而
     * {@code poems.id} 是母库 ROWID，与接口认的引用不是一回事（见 {@link Poem#getLookupRef()}）。
     * 也不能存 {@code Poem} 本身 —— 一首诗带着正文、注释、译文全文，整包走 Binder
     * 会撞 1MB 的 {@code TransactionTooLargeException}。
     */
    private String[] queueRefs = new String[0];
    private int queueIndex = -1;
    /** 进入时带了来源列表（false 即随机模式） */
    private boolean fromList;

    /** 正在异步取下一篇：挡住加载期间的重复触发 */
    private boolean switching;
    /** 取数令牌：只有最后一次请求的回调算数（同 MainActivity.searchSeq 的用法） */
    private int switchSeq;

    /**
     * 基础页签个数（译文 / 注释 / 赏析）。
     *
     * <p>它们的位置是被别处钉死的：{@code bindTools()} 里「注释」按钮写死了
     * {@code getTabAt(1)}。所以可选页签只许从尾部追加，这三个一个都不能挪、不能少。
     */
    private static final int BASE_TAB_COUNT = 3;

    /**
     * 与页签一一对应的内容种类。
     *
     * <p>可选页签按当前这首有没有数据增删，下标会跟着漂，所以内容分发认「种类」不认裸下标
     * ——否则「创作背景」插进来之后，看「赏析」会读到背景。
     */
    private final List<ContentTab> contentTabKinds = new ArrayList<>();
    /** 重建页签期间压住 {@code OnTabSelectedListener}，别在页签半成品状态下刷一次内容 */
    private boolean suppressTabCallback;

    /** 本次手势是否已经翻过一篇了：一次拖动只翻一篇，不因为一直按着就连翻 */
    private boolean swipeConsumed;
    /** {@link #SWIPE_TRIGGER_DP} 折算成的像素 */
    private float swipeTriggerPx;
    /** 手指在正文上「多出来」的纵向位移累计，只有滚动视图吃不下的部分才算得进来 */
    private float overscrollDy;
    private boolean swipeTracking;
    private float lastTouchX;
    private float lastTouchY;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    /** 循环播放的下一次开读；停播 / 关循环 / 退出页面都要撤掉 */
    private Runnable loopRunnable;
    /** 连续朗读的下一次开读；停播 / 退出页面要撤掉 */
    private Runnable nextRunnable;

    /**
     * 收藏变更监听：书架里把这首移出了、或在别处取消了收藏，
     * 这个页面的 ♡ 也要立刻跟着变，而不是等到下次进页面才对。
     */
    private final UserStore.FavoriteListener favoriteListener = new UserStore.FavoriteListener() {
        @Override
        public void onFavoritesChanged() {
            updateFavoriteIcon();
        }
    };

    /**
     * 不带来源列表地打开：上下滑动是**随机**换一首。
     *
     * <p>发现页 / 作者详情 / 朗读历史走这条；凡是从某个列表点进来的，都该用
     * {@link #openInList}，否则「下一首」会跑到别的分类去。
     */
    public static void open(@NonNull Context context, @NonNull Poem poem) {
        Intent intent = new Intent(context, DetailActivity.class);
        intent.putExtra(EXTRA_POEM, poem);
        context.startActivity(intent);
    }

    /**
     * 带上来源列表打开：上滑 / 下滑沿 {@code list} 的顺序翻。
     * 到头就停住并提示，既不循环也不跳到随机。
     *
     * <p>只把引用传过去，不传 {@code Poem}：书架的收藏数没有上限，而一首诗带着正文、
     * 注释、译文全文，整包走 Binder 会撞 1MB 的 {@code TransactionTooLargeException}。
     * 翻页时再用 {@code poemByRef} 异步取回完整内容。
     *
     * @param list 来源列表，按界面上的展示顺序；找不到 {@code poem} 时退化成随机模式
     */
    public static void openInList(@NonNull Context context, @NonNull List<Poem> list,
                                  @NonNull Poem poem) {
        Intent intent = new Intent(context, DetailActivity.class);
        intent.putExtra(EXTRA_POEM, poem);
        String[] refs = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            refs[i] = list.get(i).getLookupRef();
        }
        // 用 indexOf 找自己：Poem.equals 按 identityKey（uid 优先）比，比 id 稳
        int index = list.indexOf(poem);
        if (index >= 0) {
            intent.putExtra(EXTRA_QUEUE, refs);
            intent.putExtra(EXTRA_INDEX, index);
            intent.putExtra(EXTRA_LIST_MODE, true);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = PoetryRepository.get(this);
        store = repository.store();
        store.addFavoriteListener(favoriteListener);
        Speaker.get().init(this);

        poem = getIntent().getParcelableExtra(EXTRA_POEM);
        if (poem == null) {
            Toast.makeText(this, R.string.search_no_match, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        restoreQueue();

        verticalMode = store.isVerticalReading();
        pinyinShown = store.isPinyinShown();
        loopPlay = store.isLoopPlay();
        fontFamily = store.getFontFamily();
        fontScale = store.getFontScale();

        repository.recordHistory(poem);
        // 拼音表约 96 KB，进页面就后台载入，点开注音时无需等待
        Pinyin.warm(this);

        bindHeader();
        bindBody();
        bindTools();
        bindTabs();
        bindPlayer();
        bindGestures();
        // 列表接口只给 brief（不含 body），从列表点进来时正文是空的 —— 补一次详情
        loadFullDetailIfNeeded();
        // 偏好里打开了「进入详情自动朗读」，等页面稳住后自动开读
        if (store.isAutoPlay()) {
            binding.getRoot().postDelayed(this::startPlay, 600);
        }
    }

    /**
     * 从 Intent 里恢复翻页链条。
     *
     * <p>没有链条（发现页 / 作者详情 / 朗读历史）时，拿当前这首起一个只有一项的链，
     * 下标归零 —— 这样「列表模式」和「随机模式」共用同一套越界判断，
     * 区别只剩「往后没有了是提示还是再抽一首」。
     */
    private void restoreQueue() {
        String[] queue = getIntent().getStringArrayExtra(EXTRA_QUEUE);
        int index = getIntent().getIntExtra(EXTRA_INDEX, -1);
        fromList = getIntent().getBooleanExtra(EXTRA_LIST_MODE, false);
        if (queue != null && queue.length > 0 && index >= 0 && index < queue.length) {
            queueRefs = queue;
            queueIndex = index;
            return;
        }
        queueRefs = new String[]{poem.getLookupRef()};
        queueIndex = 0;
        fromList = false;
    }

    // ---------------------------------------------------------------- 头部

    private void bindHeader() {
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        // 顶部标题栏平时留白，正文标题滚出屏幕后才显现，避免标题重复
        binding.toolbar.setTitle("");
        binding.toolbar.setOnMenuItemClickListener(this::onToolbarAction);
        binding.detailScroll.setOnScrollChangeListener(
                (NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldX, oldY) -> {
                    int threshold = binding.poemTitle.getBottom();
                    CharSequence title = scrollY > threshold ? poem.getTitle() : "";
                    if (!title.equals(binding.toolbar.getTitle())) {
                        binding.toolbar.setTitle(title);
                    }
                });

        binding.poemTitle.setText(poem.getTitle());
        binding.poemBadge.setText(poem.getKind().getLabel());
        ViewCompat_setBadgeTint();
        binding.poemBadge.setTextColor(ContextCompat.getColor(this, poem.getKind().getColorRes()));
        binding.poemAuthor.setText(poem.getAuthorLabel());
        binding.poemMeta.setText(getString(R.string.detail_meta,
                poem.getLineCount(), poem.getNChar()));

        updateFavoriteIcon();

        if (STRAIN_ENABLED && !poem.getStrain().isEmpty()) {
            binding.strainRow.setVisibility(View.VISIBLE);
            binding.strainText.setText(prettyStrain(poem.getStrain()));
        } else {
            // 翻到一首没有平仄的，要把上一首的平仄行收掉
            binding.strainRow.setVisibility(View.GONE);
        }
    }

    private void ViewCompat_setBadgeTint() {
        androidx.core.view.ViewCompat.setBackgroundTintList(binding.poemBadge,
                android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this,
                        poem.getKind().getLightColorRes())));
    }

    private boolean onToolbarAction(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_favorite) {
            toggleFavorite();
            return true;
        }
        return false;
    }

    private void toggleFavorite() {
        // 撤销要认准点这一下时的那首：翻页之后字段 poem 已经换了人，
        // 闭包直接读字段会把「撤销」用在错误的那首上
        final Poem target = poem;
        boolean added = repository.toggleFavorite(target);
        updateFavoriteIcon();
        String message = getString(added ? R.string.shelf_added : R.string.shelf_removed);
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo, view -> {
                    repository.toggleFavorite(target);
                    updateFavoriteIcon();
                })
                .show();
    }

    private void updateFavoriteIcon() {
        boolean favorite = repository.isFavorite(poem);
        MenuItem item = binding.toolbar.getMenu().findItem(R.id.action_favorite);
        if (item != null) {
            item.setIcon(favorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            item.setTitle(getString(R.string.cd_favorite));
        }
    }

    /**
     * 平仄的视觉替换。
     *
     * <p>服务端给的 {@code strain} 已经解成可读符号，形如「仄仄平○仄，仄仄○平仄。」
     * （码表见后端 {@code internal/service/poem_service.go} 的 {@code strainSymbols}：
     * 仄 / 平 / ，/ 。/ ○ 可平可仄 / ？ 未知 / 通）。这里只把它换成更紧凑的记号：
     * 平 → —、仄 → ｜、可平可仄 → ○、未知 → ◇，标点与其余字符原样保留。
     *
     * <p><b>为什么同时保留数字分支：</b>更早的一版约定是「1 平 / 0 仄 / 3 平韵 / 4 仄韵」
     * 的数字编码，设备上残留的旧本地库 {@code poetry.db} 里可能还有那种格式；
     * 两边都认一下，免得换库时又整行渲染成 ◇。
     *
     * <p>⚠️ 2026-10-09 修正：原来只认数字编码，而后端早已改成直接返回解好的符号，
     * 于是每个字符都落到 {@code default} —— 真机实测平仄行是「◇◇◇◇◇◇…」。
     */
    @NonNull
    private String prettyStrain(@NonNull String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '平':            // 平
                case '1':
                    sb.append('—');
                    break;
                case '仄':            // 仄
                case '0':
                    sb.append('｜');
                    break;
                case '○':            // ○ 可平可仄
                    sb.append('○');
                    break;
                case '3':
                    sb.append('◎');
                    break;
                case '4':
                    sb.append('●');
                    break;
                case '？':            // ？ 未知（全角）
                case '?':                 // 未知（半角，码表 7~15）
                    sb.append('◇');
                    break;
                default:                  // ，。通 等原样保留
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 上下滑动翻篇

    /**
     * 在正文滚动视图上「只观察、不消费」地识别越界拖动。
     *
     * <p>不能用 {@code GestureDetector.onFling} 配合 {@code canScrollVertically} 判断：
     * 一次普通的快速上滑在 {@code onFling} 触发时，滚动**已经**把该滚的距离吃掉了，
     * 此刻 {@code canScrollVertically(1)} 正是 false —— 于是「正常滚到底」会被误判成翻页。
     * 挂在 ACTION_DOWN 上判断同样不对（离底还有 20px 时它依然为 true）。
     *
     * <p>改用位移累计：每来一个 MOVE 都问一次「这个方向还能滚吗」，能滚就把累计清零
     * （说明滚动视图正在消费它），不能滚才计入累计。累计越过 {@link #SWIPE_TRIGGER_DP}
     * 才算翻页。回调恒返回 false，正文滚动、拼音 / 竖排的横向滚动、点按都原样放行。
     */
    private void bindGestures() {
        swipeTriggerPx = SWIPE_TRIGGER_DP * getResources().getDisplayMetrics().density;
        binding.detailScroll.setOnTouchListener(swipeListener);
        // 注音 / 竖排时正文其实装在这两个横向滚动视图里：手指落在它们上面，它们会接走
        // 整个手势（DOWN 就被它们消费了），外层 detailScroll 的监听器根本不会响 ——
        // 所以三个都要挂。一次手势只有落点所在的那一个会响，不会重复触发。
        binding.pinyinScroll.setOnTouchListener(swipeListener);
        binding.verticalScroll.setOnTouchListener(swipeListener);
    }

    private final View.OnTouchListener swipeListener = (v, event) -> {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeTracking = true;
                // 一次手势只翻一页：只有新的 DOWN 才解锁下一次
                swipeConsumed = false;
                overscrollDy = 0f;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE: {
                float x = event.getX();
                float y = event.getY();
                if (!swipeTracking) {
                    // 这一轮手势的 DOWN 没经过这里（落点被别的视图接走了），或者中途
                    // 被 CANCEL 过（翻页会重建正文，框架可能把触摸目标摘掉）。两种情况下
                    // lastTouch 都是上一段的残留坐标，直接算差值会得出一个巨大的假位移，
                    // 一次滑动就翻出两页 —— 所以这里只把基准线对齐到当前位置，
                    // 这一次位移丢掉不算。
                    swipeTracking = true;
                    overscrollDy = 0f;
                    lastTouchX = x;
                    lastTouchY = y;
                    break;
                }
                float dx = x - lastTouchX;
                float dy = y - lastTouchY;
                lastTouchX = x;
                lastTouchY = y;
                if (swipeConsumed || Math.abs(dy) < Math.abs(dx)) {
                    // 这一次拖动已经翻过了；或者明显是横向拖动（拼音行、竖排的左右滚动）
                    break;
                }
                boolean atBottom = !binding.detailScroll.canScrollVertically(1);
                boolean atTop = !binding.detailScroll.canScrollVertically(-1);
                if (dy < 0 && atBottom) {
                    overscrollDy += dy;
                } else if (dy > 0 && atTop) {
                    overscrollDy += dy;
                } else {
                    overscrollDy = 0f;
                    break;
                }
                if (overscrollDy <= -swipeTriggerPx) {
                    swipeConsumed = true;
                    advance(1, shouldKeepPlaying());
                } else if (overscrollDy >= swipeTriggerPx) {
                    swipeConsumed = true;
                    advance(-1, shouldKeepPlaying());
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                swipeTracking = false;
                overscrollDy = 0f;
                // 这里不复位 swipeConsumed：翻页会重建正文，框架可能顺手给一个 CANCEL，
                // 但那还是同一次手指滑动，不能因此解锁第二次翻页
                break;
            default:
                break;
        }
        return false;
    };

    /** 翻页前正在朗读，或者用户开了「进入详情自动朗读」，翻过去就接着读 */
    private boolean shouldKeepPlaying() {
        return playing || store.isAutoPlay();
    }

    /**
     * 翻一篇。{@code direction}：+1 下一首，-1 上一首。
     *
     * @param playAfter 翻过去之后要不要直接开读
     */
    private void advance(int direction, boolean playAfter) {
        if (switching) {
            return;
        }
        int target = queueIndex + direction;
        if (target < 0) {
            // 列表模式下这是「第一首」这个明确的边界；
            // 随机模式下则意味着这条随机链还没抽出更早的一首
            Toast.makeText(this, fromList ? R.string.detail_first_poem : R.string.detail_no_prev,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (target < queueRefs.length) {
            loadAndShow(queueRefs[target], target, direction, playAfter);
            return;
        }
        if (fromList) {
            Toast.makeText(this, R.string.detail_last_poem, Toast.LENGTH_SHORT).show();
            return;
        }
        // 随机模式：往后没有现成的，现抽一首接在链条末尾，于是它也能往回翻
        switching = true;
        final int seq = ++switchSeq;
        repository.randomPoem(poem.getLookupRef(), new Callback<Poem>() {
            @Override
            public void onData(@NonNull Poem data) {
                if (seq != switchSeq) {
                    return;
                }
                String[] grown = Arrays.copyOf(queueRefs, queueRefs.length + 1);
                grown[queueRefs.length] = data.getLookupRef();
                applyPoem(data, grown, grown.length - 1, direction, playAfter);
            }

            @Override
            public void onError(@Nullable Throwable error) {
                if (seq != switchSeq) {
                    return;
                }
                switching = false;
                Toast.makeText(DetailActivity.this, R.string.detail_switch_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 按引用取回完整内容再显示（队列里存的是引用，不是 Poem） */
    private void loadAndShow(@NonNull String ref, int index, int direction, boolean playAfter) {
        switching = true;
        final int seq = ++switchSeq;
        repository.poemByRef(ref, new Callback<Poem>() {
            @Override
            public void onData(@NonNull Poem data) {
                if (seq != switchSeq) {
                    return;
                }
                applyPoem(data, queueRefs, index, direction, playAfter);
            }

            @Override
            public void onError(@Nullable Throwable error) {
                if (seq != switchSeq) {
                    return;
                }
                switching = false;
                Toast.makeText(DetailActivity.this, R.string.detail_switch_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 换到这一首：只重刷与内容有关的部分。
     *
     * <p>{@code bindTabs()} 绝不能重跑（那是「建表」，会把三个基础页签再建一遍），
     * 页签的增删走 {@link #syncOptionalTabs()}；{@code bindTools()} /
     * {@code bindPlayer()} 只设监听和与诗无关的开关态，也只在 {@code onCreate} 里跑一次。
     *
     * <p>顺手把新状态写回 Intent：本页没有声明 {@code configChanges}，
     * 转屏、切夜间模式、换皮肤都会带着同一个 Intent 重走 {@code onCreate}，
     * 不回写就会莫名其妙跳回进来时那一首。
     */
    private void applyPoem(@NonNull Poem next, @NonNull String[] queue, int index, int direction,
                           boolean playAfter) {
        switching = false;
        if (isFinishing() || isDestroyed()) {
            // 取数期间页面已经退出了：别再刷界面，更别把朗读开起来
            return;
        }
        // 队列一并收下：随机模式下它是「这次新抽的那首接在末尾」的新数组，
        // 不写回字段的话队列永远只有进来时那一首，下滑会一步跳回入口那首
        queueRefs = queue;
        queueIndex = index;
        stopPlay();
        poem = next;

        getIntent().putExtra(EXTRA_POEM, poem);
        getIntent().putExtra(EXTRA_QUEUE, queueRefs);
        getIntent().putExtra(EXTRA_INDEX, queueIndex);
        getIntent().putExtra(EXTRA_LIST_MODE, fromList);

        // 翻到哪首就算读过哪首
        repository.recordHistory(poem);
        bindHeader();
        updateBodyView();
        updateNowPlaying();
        // 页签跟着这首的内容变（有背景/简介才多出那两个），内容也在里面一并刷新
        syncOptionalTabs();
        // 进度条要归零：不归零会留着上一首读到一半的位置
        binding.playProgress.setProgress(0);
        binding.detailScroll.scrollTo(0, 0);
        playSwipeAnimation(direction);
        if (playAfter) {
            startPlay();
        }
    }

    /** 正文从手指离开的那一侧轻轻推进来，让「换了一页」这件事看得见 */
    private void playSwipeAnimation(int direction) {
        View target = binding.detailScroll;
        float offset = target.getHeight() * 0.12f * (direction > 0 ? 1f : -1f);
        target.setTranslationY(offset);
        target.setAlpha(0.45f);
        target.animate().translationY(0f).alpha(1f).setDuration(180L).start();
    }

    // ---------------------------------------------------------------- 正文

    /**
     * 进入本页时补拉一次完整内容。
     *
     * <p>列表接口给的是 {@code PoemBrief} —— 有 {@code excerpt}、没有 {@code body}
     * （见后端 {@code PoemBrief} 的注释，刻意的，免得列表把全文都拖下来）。
     * 而本页要渲染正文 / 译文 / 注释 / 赏析，所以从列表点进来这条路径必须再取一次。
     * {@code JsonMapper} 的类注释把这件事写成「详情页靠 {@code getBody().isEmpty()}
     * 判断要不要补一次详情请求」—— 这个方法就是那句话的实现。
     *
     * <p>发现页 / 随机 / 朗读历史进来时正文已经带着，直接跳过，不多发请求。
     *
     * <p>刻意不调 {@link #applyPoem}：它里面有 {@code stopPlay()}，会把「进入自动朗读」
     * 刚起来的那次朗读掐断；还会重置滚动位置、播翻页动画 —— 首次进入都不需要。
     */
    private void loadFullDetailIfNeeded() {
        if (!poem.getBody().isEmpty()) {
            return;
        }
        final String ref = poem.getLookupRef();
        repository.poemByRef(ref, new Callback<Poem>() {
            @Override
            public void onData(@NonNull Poem data) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                // 取数期间可能已翻到别首（或转屏重建），只认当前这首的结果
                if (!ref.equals(poem.getLookupRef())) {
                    return;
                }
                poem = data;
                getIntent().putExtra(EXTRA_POEM, poem);
                bindHeader();
                updateBodyView();
                syncOptionalTabs();
                // syncOptionalTabs 在页签数量没变时会提前 return、也就不刷内容，
                // 所以这里补一次当前页签 —— 否则译文区会一直停着补拉前的占位文案
                showContent(binding.contentTabs.getSelectedTabPosition());
                updateNowPlaying();
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // 补不到就维持 brief 渲染；正文区给个明确提示，别留一片空白
                binding.poemBody.setText(R.string.detail_no_content);
            }
        });
    }

    private void bindBody() {
        updateBodyView();
    }

    /** 正文字号唯一来源：样式里的 text_xl（24sp）× 用户缩放 */
    private float poemCharSize() {
        return getResources().getDimension(R.dimen.text_xl) * fontScale;
    }

    @NonNull
    private Typeface currentTypeface() {
        return "sans".equals(fontFamily) ? Typeface.SANS_SERIF : Typeface.SERIF;
    }

    /**
     * 三种展示形态互斥：
     * 带注音 → PinyinView（横排/竖排都支持）；否则竖排 → 分列 TextView；否则 → 普通 TextView。
     */
    private void updateBodyView() {
        boolean pinyin = pinyinShown;
        binding.poemBody.setVisibility(!pinyin && !verticalMode ? View.VISIBLE : View.GONE);
        binding.pinyinScroll.setVisibility(pinyin ? View.VISIBLE : View.GONE);
        binding.verticalScroll.setVisibility(!pinyin && verticalMode ? View.VISIBLE : View.GONE);

        if (pinyin) {
            // 一次设完全部样式：只重排一遍，切换竖排/注音时不会看到中间态闪一下
            binding.pinyinBody.setStyle(currentTypeface(), poemCharSize(),
                    verticalMode ? PinyinView.ORIENT_VERTICAL : PinyinView.ORIENT_HORIZONTAL,
                    poem.getBody());
            binding.pinyinBody.setTextColor(ContextCompat.getColor(this, R.color.ink_900));
            binding.pinyinBody.setPinyinColor(ContextCompat.getColor(this, R.color.ink_500));
            return;
        }
        if (verticalMode) {
            renderVertical();
        } else {
            binding.poemBody.setTypeface(currentTypeface());
            binding.poemBody.setTextSize(TypedValue.COMPLEX_UNIT_PX, poemCharSize());
            binding.poemBody.setText(poem.getBody());
        }
    }

    /** 竖排：一句一列，自右向左排列（中文传统排版） */
    private void renderVertical() {
        binding.verticalContainer.removeAllViews();
        List<String> lines = poem.getLines();
        float size = poemCharSize();
        // 自右向左：按逆序添加，第一行最终落在最右侧
        for (int i = lines.size() - 1; i >= 0; i--) {
            TextView column = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(getResources().getDimensionPixelSize(R.dimen.space_2));
            column.setLayoutParams(lp);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setTypeface(currentTypeface());
            column.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            column.setTextColor(ContextCompat.getColor(this, R.color.ink_900));
            column.setIncludeFontPadding(false);
            column.setLineSpacing(0f, 1.35f);

            StringBuilder sb = new StringBuilder();
            String line = lines.get(i);
            for (int c = 0; c < line.length(); c++) {
                if (c > 0) {
                    sb.append('\n');
                }
                sb.append(line.charAt(c));
            }
            column.setText(sb.toString());
            binding.verticalContainer.addView(column);
        }
    }

    private void applyFontScale() {
        updateBodyView();
    }

    // ---------------------------------------------------------------- 工具条

    private void bindTools() {
        setToggle(binding.toolVertical, verticalMode);
        binding.toolVertical.setOnClickListener(v -> {
            verticalMode = !verticalMode;
            store.setVerticalReading(verticalMode);
            setToggle(binding.toolVertical, verticalMode);
            updateBodyView();
        });

        binding.toolFontSmaller.setOnClickListener(v -> {
            fontScale = Math.max(0.85f, fontScale - 0.15f);
            store.setFontScale(fontScale);
            applyFontScale();
        });
        binding.toolFontLarger.setOnClickListener(v -> {
            fontScale = Math.min(1.6f, fontScale + 0.15f);
            store.setFontScale(fontScale);
            applyFontScale();
        });

        binding.toolFontFamily.setText(fontFamilyLabel());
        binding.toolFontFamily.setOnClickListener(v -> {
            boolean serif = "serif".equals(fontFamily);
            fontFamily = serif ? "sans" : "serif";
            store.setFontFamily(fontFamily);
            binding.toolFontFamily.setText(fontFamilyLabel());
            Toast.makeText(this, getString(R.string.font_switched, fontFamilyLabel()),
                    Toast.LENGTH_SHORT).show();
            updateBodyView();
        });

        binding.toolNote.setOnClickListener(v -> {
            TabLayout.Tab tab = binding.contentTabs.getTabAt(1);
            if (tab != null) {
                tab.select();
            }
            binding.contentTabs.requestFocus();
        });

        setToggle(binding.toolPinyin, pinyinShown);
        binding.toolPinyin.setOnClickListener(v -> {
            pinyinShown = !pinyinShown;
            store.setPinyinShown(pinyinShown);
            setToggle(binding.toolPinyin, pinyinShown);
            updateBodyView();
            Toast.makeText(this, pinyinShown ? R.string.pinyin_shown : R.string.pinyin_hidden,
                    Toast.LENGTH_SHORT).show();
        });
    }

    @NonNull
    private String fontFamilyLabel() {
        return getString("sans".equals(fontFamily)
                ? R.string.font_family_sans : R.string.font_family_serif);
    }

    /** 开关型工具按钮的选中态：选中不透明，未选中半透明 */
    private void setToggle(@NonNull View view, boolean selected) {
        view.setSelected(selected);
        view.setAlpha(selected ? 1f : 0.55f);
    }

    // ------------------------------------------------- 译文 / 注释 / 赏析 / 创作背景 / 简介

    /** 详情页内容页签的种类：下标会变，种类不会 */
    private enum ContentTab {
        TRANSLATION, NOTES, APPRECIATION, BACKGROUND, INTRODUCTION
    }

    private void bindTabs() {
        contentTabKinds.clear();
        appendTab(ContentTab.TRANSLATION, R.string.tab_translation);
        appendTab(ContentTab.NOTES, R.string.tab_annotation);
        appendTab(ContentTab.APPRECIATION, R.string.tab_appreciation);
        binding.contentTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (suppressTabCallback) {
                    return;
                }
                showContent(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                // ignore
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                if (suppressTabCallback) {
                    return;
                }
                showContent(tab.getPosition());
            }
        });
        // 进来这一首要是带背景/简介，页签在这步就补齐；不带就还是 3 个
        syncOptionalTabs();
        showContent(0);
    }

    /**
     * 追加一个页签。
     *
     * <p>先记种类再 {@code addTab}：往空表里加第一个页签时 TabLayout 会顺手把它选中并回调
     * {@code showContent}，那时种类清单里必须已经有对应的那一项。
     */
    private void appendTab(@NonNull ContentTab kind, int titleRes) {
        contentTabKinds.add(kind);
        binding.contentTabs.addTab(binding.contentTabs.newTab().setText(titleRes));
    }

    /**
     * 按当前这首的内容增删「创作背景 / 简介」两个可选页签。
     *
     * <p>两个方向都要能：{@code poem_extras} 的富文本是逐首可选的，有背景没简介、有简介没背景
     * 都很常见，所以「换一首」和左右滑页之后页签数量得跟着变。
     *
     * <p>只从尾部删——前三个的位置不能动（见 {@link #BASE_TAB_COUNT}）。
     * 重建期间压住回调，最后按「种类」把用户原先看的那一页选回来；
     * 原先那一页没了（从有背景的一首翻到没有的），退回第一个页签。
     */
    private void syncOptionalTabs() {
        boolean wantBackground = !poem.getCreativeBackground().isEmpty();
        boolean wantIntroduction = !poem.getIntroduction().isEmpty();
        boolean hasBackground = contentTabKinds.contains(ContentTab.BACKGROUND);
        boolean hasIntroduction = contentTabKinds.contains(ContentTab.INTRODUCTION);
        if (wantBackground == hasBackground && wantIntroduction == hasIntroduction) {
            // 页签没变就别动：白重建一次会平白刷掉用户正在看的那一页
            return;
        }

        ContentTab selected = selectedKind();
        suppressTabCallback = true;
        try {
            while (contentTabKinds.size() > BASE_TAB_COUNT) {
                contentTabKinds.remove(contentTabKinds.size() - 1);
                binding.contentTabs.removeTabAt(contentTabKinds.size());
            }
            if (wantBackground) {
                appendTab(ContentTab.BACKGROUND, R.string.tab_creative_background);
            }
            if (wantIntroduction) {
                appendTab(ContentTab.INTRODUCTION, R.string.tab_introduction);
            }
        } finally {
            suppressTabCallback = false;
        }

        int index = selected == null ? 0 : contentTabKinds.indexOf(selected);
        if (index < 0) {
            index = 0;
        }
        TabLayout.Tab tab = binding.contentTabs.getTabAt(index);
        if (tab != null) {
            // 已经选中的页签 select() 不会再回调（只有 reselected），所以下面无条件再刷一次
            tab.select();
        }
        showContent(index);
    }

    /** 当前选中的页签种类；还没选中（或下标越界）时为 null */
    @Nullable
    private ContentTab selectedKind() {
        int position = binding.contentTabs.getSelectedTabPosition();
        return position >= 0 && position < contentTabKinds.size()
                ? contentTabKinds.get(position) : null;
    }

    private void showContent(int position) {
        if (position < 0 || position >= contentTabKinds.size()) {
            return;
        }
        switch (contentTabKinds.get(position)) {
            case NOTES:
                showNotes();
                break;
            case APPRECIATION:
                setContentText(poem.getAppreciation());
                break;
            case BACKGROUND:
                setContentText(poem.getCreativeBackground());
                break;
            case INTRODUCTION:
                setContentText(poem.getIntroduction());
                break;
            case TRANSLATION:
            default:
                setContentText(poem.getTranslation());
                break;
        }
    }

    /** 注释：一条一行，前面缀上序号 */
    private void showNotes() {
        List<String> notes = poem.getNoteList();
        if (notes.isEmpty()) {
            binding.contentText.setText(R.string.detail_no_notes);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            sb.append(i + 1).append(". ").append(notes.get(i));
            if (i < notes.size() - 1) {
                sb.append("\n\n");
            }
        }
        binding.contentText.setText(sb.toString());
    }

    /** 富文本为空时统一用「内容待下载」占位 */
    private void setContentText(String text) {
        binding.contentText.setText(text == null || text.isEmpty()
                ? getString(R.string.detail_no_content) : text);
    }

    // ---------------------------------------------------------------- 朗读

    private void bindPlayer() {
        updateNowPlaying();
        updateVoiceLabel();
        updatePlayIcon();

        binding.playButton.setOnClickListener(v -> {
            if (playing) {
                stopPlay();
            } else {
                startPlay();
            }
        });

        binding.voiceButton.setOnClickListener(v -> VoiceSheet.show(this,
                new VoiceSheet.Callback() {
                    @Override
                    public void onSelected(@NonNull com.example.poetry.data.model.Voice voice) {
                        updateVoiceLabel();
                        Toast.makeText(DetailActivity.this,
                                getString(R.string.player_voice, voice.getName()),
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onPreview(@NonNull com.example.poetry.data.model.Voice voice) {
                        startPreview();
                    }
                }));

        setToggle(binding.loopButton, loopPlay);
        binding.loopButton.setOnClickListener(v -> {
            loopPlay = !loopPlay;
            store.setLoopPlay(loopPlay);
            setToggle(binding.loopButton, loopPlay);
            Toast.makeText(this, loopPlay ? R.string.player_loop_on
                    : R.string.player_loop_off, Toast.LENGTH_SHORT).show();
            if (!loopPlay) {
                cancelLoop();
            }
        });

        Speaker.get().setListener(playerListener);
    }

    /** 底部「正在朗读」文案：翻页后要跟着换 */
    private void updateNowPlaying() {
        binding.nowPlaying.setText(getString(R.string.now_reading, poem.getTitle()));
    }

    /** 朗读状态回调：从设置页返回时单例上的监听会被置空，因此每次播放前重新挂上 */
    private final Speaker.Listener playerListener = new Speaker.Listener() {
        @Override
        public void onStart() {
            playing = true;
            updatePlayIcon();
            startProgress();
        }

        @Override
        public void onDone() {
            playing = false;
            updatePlayIcon();
            stopProgress();
            binding.playProgress.setProgress(100);
            store.setProgress(poem, 100);
            scheduleNext();
        }

        @Override
        public void onError(String message) {
            playing = false;
            updatePlayIcon();
            stopProgress();
            cancelLoop();
            cancelNext();
            Toast.makeText(DetailActivity.this, message, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onNotice(String message) {
            // 只是换了个声音，这一遍还在读——不动 playing、不碰进度条，说一声就够了
            Toast.makeText(DetailActivity.this, message, Toast.LENGTH_LONG).show();
        }
    };

    /**
     * 试听专用的回调——只报错，不碰任何播放状态。
     * <p>
     * 试听和正文朗读共用同一只引擎，而 {@link Speaker} 身上只挂得下一个 listener。
     * 试听期间如果还挂着 {@link #playerListener}，那句「明月几时有」的 onStart / onDone
     * 会被当成这首诗读完了：进度条直接推到 100、{@code store.setProgress(poem, 100)}
     * 把这诗标记成已读完，开着「连续朗读」还会顺势翻到下一篇。
     */
    private final Speaker.Listener previewListener = new Speaker.Listener() {
        @Override
        public void onStart() {
            // 刻意什么都不做：进度条量的是整首诗，不能被一句试听带起节奏
        }

        @Override
        public void onDone() {
            // 试听读完了，把正文的 listener 换回去
            Speaker.get().setListener(playerListener);
        }

        @Override
        public void onError(String message) {
            Speaker.get().setListener(playerListener);
            Toast.makeText(DetailActivity.this, message, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onNotice(String message) {
            Toast.makeText(DetailActivity.this, message, Toast.LENGTH_LONG).show();
        }
    };

    /**
     * 试听。正在朗读的话先把当前这首停下来——引擎只有一条声道，试听必然会把朗读打断，
     * 与其让进度条在没声音的情况下继续爬，不如明确地停下来。
     */
    private void startPreview() {
        if (playing) {
            stopPlay();
        }
        Speaker.get().setListener(previewListener);
        Speaker.get().speak(getString(R.string.tts_preview_text));
    }

    private void updateVoiceLabel() {
        // 取引擎归一之后的音色，而不是存下来的那个：未开通会员时，存着的云端音色不会真的发声，
        // 引擎已经退回离线音色（见 EngineRouter#currentVoiceId）。拿存下来的 id 去找名字，
        // 标签上写着「智瑞」、耳朵里听见的却是另一个音色——这个标签的职责是「谁在读」，
        // 不是「上次选过谁」。归一在 Speaker.apply 里同步过来，启动时就跑过了（见 PoetryApp）。
        String voiceId = Speaker.get().currentVoiceId();
        if (voiceId == null || voiceId.isEmpty()) {
            voiceId = store.getTtsConfig().getVoiceId();
        }
        String name = voiceId.isEmpty() ? getString(R.string.voice_default_name) : "";
        if (name.isEmpty()) {
            for (com.example.poetry.data.model.Voice voice : Speaker.get().listVoices()) {
                if (voice.getId().equals(voiceId)) {
                    name = voice.getName();
                    break;
                }
            }
        }
        if (name.isEmpty()) {
            // 存着的音色在当前语音包里已经不存在了（换过包，或者是旧版的 sherpa 音色）：
            // 与其把内部 id 当名字显示给用户，不如说清楚它就是「默认」
            name = getString(R.string.voice_default_name);
        }
        binding.voiceLabel.setText(getString(R.string.player_voice, name));
    }

    private void updatePlayIcon() {
        binding.playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        binding.playButton.setContentDescription(getString(playing ? R.string.cd_pause : R.string.cd_play));
    }

    private void startPlay() {
        if (!Speaker.get().isReady()) {
            // 语音包没加载起来（模型还在拷、或 assets 里就没有）就没有声音可放；
            // 直接把用户送到语音设置页，那里会讲清楚是哪一种情况
            Snackbar.make(binding.getRoot(), R.string.tts_unavailable, Snackbar.LENGTH_LONG)
                    .setAction(R.string.tts_go_settings,
                            v -> VoiceSettingsActivity.open(this))
                    .show();
            return;
        }
        // 直接应用整份配置：引擎 + 发音人 + 语速 + 音调 + 音量
        Speaker.get().apply(store.getTtsConfig());
        Speaker.get().setListener(playerListener);
        // 按日期记一次朗读：同一天里同一首只累加次数
        store.recordPlay(poem);
        // 换行原样交给引擎：TextChunker 要按诗行分「联」，替成「。」就没法分了
        Speaker.get().speak(poem.getBody());
    }

    private void stopPlay() {
        cancelLoop();
        cancelNext();
        Speaker.get().stop();
        playing = false;
        updatePlayIcon();
        stopProgress();
    }

    /**
     * 读完一首之后的去向，隔 1.2 秒再动身 —— 一口气接上去听不出「这一遍结束了」。
     *
     * <p>单首循环优先：「循环播放」开着就还是这一首。否则看「连续朗读」
     * （偏好里默认开着，文案是「一首读完自动接着读下一篇」），翻下一篇接着读。
     * 列表模式下读到最后一首就停在那儿，随机模式则一直读下去。
     */
    private void scheduleNext() {
        cancelLoop();
        cancelNext();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (loopPlay) {
            loopRunnable = new Runnable() {
                @Override
                public void run() {
                    loopRunnable = null;
                    startPlay();
                }
            };
            handler.postDelayed(loopRunnable, 1200L);
            return;
        }
        if (!store.isContinuousPlay()) {
            return;
        }
        nextRunnable = new Runnable() {
            @Override
            public void run() {
                nextRunnable = null;
                advance(1, true);
            }
        };
        handler.postDelayed(nextRunnable, 1200L);
    }

    private void cancelLoop() {
        if (loopRunnable != null) {
            handler.removeCallbacks(loopRunnable);
            loopRunnable = null;
        }
    }

    private void cancelNext() {
        if (nextRunnable != null) {
            handler.removeCallbacks(nextRunnable);
            nextRunnable = null;
        }
    }

    /**
     * 按字数 + 语速 + 停顿估算朗读时长，进度条平滑推进。
     * 口径由 {@code Speaker.estimateDurationMs} 按当前引擎（离线/云端）自己挑，否则会先跑满。
     */
    private void startProgress() {
        stopProgress();
        long durationMs = Speaker.get().estimateDurationMs(
                poem.getBody(), store.getTtsConfig().getRate());
        long start = System.currentTimeMillis();
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                int percent = (int) ((System.currentTimeMillis() - start) * 100 / durationMs);
                if (percent > 100) {
                    percent = 100;
                }
                binding.playProgress.setProgress(percent);
                store.setProgress(poem, percent);
                if (percent < 100 && playing) {
                    handler.postDelayed(this, 200);
                }
            }
        };
        handler.post(progressRunnable);
    }

    private void stopProgress() {
        if (progressRunnable != null) {
            handler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    @Override
    protected void onDestroy() {
        store.removeFavoriteListener(favoriteListener);
        super.onDestroy();
        cancelLoop();
        cancelNext();
        stopProgress();
        Speaker.get().setListener(null);
        Speaker.get().stop();
    }
}
