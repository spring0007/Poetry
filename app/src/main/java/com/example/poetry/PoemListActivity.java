package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.poetry.ui.Skin;
import com.example.poetry.adapter.PoemCardAdapter;
import com.example.poetry.data.DbStatus;
import com.example.poetry.data.DbStatusListener;
import com.example.poetry.data.PoemQuery;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.model.Page;
import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ActivityPoemListBinding;

/**
 * 分类结果列表页：按体裁 / 朝代 / 主题 / 作者筛选后的作品列表。
 * <p>
 * 由分类页、朝代宫格、主题 chip、作者头像四种入口复用。
 *
 * <h3>取数与翻页</h3>
 * 一页 {@link #PAGE_SIZE} 条，**本地先出图**（毫秒级），远端第一页回来再替换一次
 * （见 {@link PoetryRepository#listFirstScreen}）；滚到底自动取下一页。
 * 两种数据源的翻页是各走各的：本地那一次用 {@code LIMIT/OFFSET}，
 * 远端那一次用接口的 {@code page/size} —— 哪一层先给出内容，就往哪一层继续要。
 */
public class PoemListActivity extends AppCompatActivity {

    private static final String EXTRA_TITLE = "extra_title";
    private static final String EXTRA_MODE = "extra_mode";
    private static final String EXTRA_VALUE = "extra_value";

    /**
     * 每页条数。
     *
     * <p>原来是一次要 60 条。改成 30 是为了让**首屏**更快：本地库这一页要构造 60 个
     * {@code Poem} 对象（含正文），而用户第一眼只看得到七八张卡。剩下的滚到时再取。
     */
    private static final int PAGE_SIZE = 30;

    /** 离底部还有这么多条时就开始取下一页，滚动就不会在到底那一刻顿一下。 */
    private static final int PREFETCH_DISTANCE = 5;

    // 入口模式常量直接沿用 PoemQuery 的那一套，别在两边各写一份字面量
    public static final String MODE_KIND = PoemQuery.MODE_KIND;
    public static final String MODE_DYNASTY = PoemQuery.MODE_DYNASTY;
    public static final String MODE_THEME = PoemQuery.MODE_THEME;
    public static final String MODE_AUTHOR = PoemQuery.MODE_AUTHOR;
    public static final String MODE_SEARCH = PoemQuery.MODE_SEARCH;
    /** 热点诗词：按热度（score）降序 */
    public static final String MODE_HOT = PoemQuery.MODE_HOT;

    private ActivityPoemListBinding binding;
    private PoetryRepository repository;
    private PoemCardAdapter adapter;

    /** 筛选条件提成字段：库热重载后要按同一条件重查一遍。Intent 只在 onCreate 读一次。 */
    private String mode = MODE_KIND;
    private String value = "";
    private PoemQuery query = PoemQuery.kind("");

    /** 下一页的页号（从 1 起，所以初始是 2）。 */
    private int nextPage = 2;
    /** 当前这份列表后面还有没有。 */
    private boolean hasMore;
    /** 正在取下一页，防止滚动事件连发几次取重了。 */
    private boolean loadingMore;
    /**
     * 手上的列表是不是远端给的。
     *
     * <p>翻页得在**同一层**上翻：本地库的 OFFSET 和接口的 page 是两套游标，
     * 混着用会漏条目。所以「当前这屏从哪来」决定了下一页问谁。
     */
    private boolean remoteMode;
    /**
     * 这一屏是不是已经靠本地数据翻过页了（**真追加成功过**才算，见 {@link #loadMore}）。
     *
     * <p>用来挡住「远端第一页迟到」这种情况：本地翻到第三页时远端才回来，
     * 直接替换会把用户看着的两屏内容抹掉。宁可让这一屏停在本地数据上。
     *
     * <p>记在追加成功之后而不是发出请求时：取下一页失败的话什么都没翻出去，
     * 这时候远端第一页回来仍然应该让它刷新这一屏。
     */
    private boolean pagedBeyondFirst;

    /**
     * 诗库状态订阅者。Activity 的一生只有一份，在 {@code onCreate} 建、{@code onDestroy} 摘，
     * 转屏会走完整的销毁重建，不会漏。
     */
    private DbStatusListener readyWatcher;

    public static void open(@NonNull Context context, @NonNull String title,
                            @NonNull String mode, @NonNull String value) {
        Intent intent = new Intent(context, PoemListActivity.class);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_MODE, mode);
        intent.putExtra(EXTRA_VALUE, value);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityPoemListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = PoetryRepository.get(this);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String intentMode = getIntent().getStringExtra(EXTRA_MODE);
        String intentValue = getIntent().getStringExtra(EXTRA_VALUE);
        mode = intentMode == null ? MODE_KIND : intentMode;
        value = intentValue == null ? "" : intentValue;
        query = PoemQuery.of(mode, value);
        binding.listTitle.setText(title == null ? getString(R.string.app_name) : title);
        binding.backButton.setOnClickListener(v -> finish());

        adapter = new PoemCardAdapter(new PoemCardAdapter.Listener() {
            @Override
            public void onPoemClick(@NonNull Poem poem) {
                // 分类 / 朝代 / 主题 / 搜索 / 热点都走这一处：详情页里上下滑动
                // 就沿当前这一份列表翻，正是「分类按分类切换下一首」要的效果
                DetailActivity.openInList(PoemListActivity.this, adapter.items(), poem);
            }

            @Override
            public void onFavoriteClick(@NonNull Poem poem) {
                boolean added = repository.toggleFavorite(poem);
                android.widget.Toast.makeText(PoemListActivity.this,
                        added ? R.string.shelf_added : R.string.shelf_removed,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        // 热点榜「查看全部」进来的这一页也带热度标记，与发现页保持一致
        adapter.setHotMarkEnabled(MODE_HOT.equals(mode));
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        binding.poemList.setLayoutManager(layoutManager);
        binding.poemList.setAdapter(adapter);
        binding.poemList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) {
                    return;
                }
                if (layoutManager.findLastVisibleItemPosition()
                        >= adapter.getItemCount() - 1 - PREFETCH_DISTANCE) {
                    loadMore();
                }
            }
        });

        // 注册会立刻回调一次，ReadyWatcher 把第一次算作跃迁，首次加载就由它触发。
        readyWatcher = new DbStatusListener.ReadyWatcher() {
            @Override
            protected void onReadyChanged(boolean ready, @NonNull DbStatus status) {
                reload();
            }
        };
        repository.addDbStatusListener(readyWatcher);
    }

    /** 按当前筛选条件重查。{@code adapter.submit()} 是替换语义，重复调用安全。 */
    private void reload() {
        nextPage = 2;
        hasMore = false;
        loadingMore = false;
        remoteMode = false;
        pagedBeyondFirst = false;
        adapter.setFooterState(PoemCardAdapter.FOOTER_NONE);
        binding.loadingBar.setVisibility(View.VISIBLE);
        repository.listFirstScreen(query, PAGE_SIZE, new PoetryRepository.PageCallback() {
            @Override
            public void onPage(@NonNull Page<Poem> page, boolean fromCache) {
                if (binding == null) {
                    return;
                }
                if (!fromCache) {
                    // 本地已经翻过页了：这一屏就让用户留在本地数据上，别抽走他正在看的内容。
                    // 也**不置 remoteMode** —— 后面的页得接着本地那套 OFFSET 游标往下翻，
                    // 半路改用接口的 page 会让两套游标错位（重条 / 漏条）。
                    if (pagedBeyondFirst) {
                        return;
                    }
                    remoteMode = true;
                } else if (remoteMode) {
                    // 远端已经先到（理论上不该发生），晚到的本地页不该覆盖它
                    return;
                }
                showFirstPage(page);
            }

            @Override
            public void onError(@NonNull Throwable error) {
                if (binding == null) {
                    return;
                }
                // 能走到这里说明本地那层也没给出东西，那空态就是真实状态
                binding.loadingBar.setVisibility(View.GONE);
                binding.emptyState.getRoot().setVisibility(View.VISIBLE);
                binding.poemList.setVisibility(View.GONE);
            }
        });
    }

    private void showFirstPage(@NonNull Page<Poem> page) {
        binding.loadingBar.setVisibility(View.GONE);
        adapter.submit(page.getItems());
        nextPage = page.getPage() + 1;
        hasMore = page.hasMore();
        boolean empty = page.isEmpty();
        binding.emptyState.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.poemList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** 取下一页。滚动回调会频繁叫它，所以前两行就把不该发的挡掉。 */
    private void loadMore() {
        if (loadingMore || !hasMore || binding == null) {
            return;
        }
        loadingMore = true;
        adapter.setFooterState(PoemCardAdapter.FOOTER_LOADING);
        final int page = nextPage;
        final boolean local = !remoteMode;
        PoetryRepository.PageCallback callback = new PoetryRepository.PageCallback() {
            @Override
            public void onPage(@NonNull Page<Poem> result, boolean fromCache) {
                if (binding == null) {
                    return;
                }
                loadingMore = false;
                // 请求发出去之后远端第一页接管了这一屏（remoteMode 翻真），而这一页是
                // 本地游标取的：扔掉。两套游标混在一起就是重条和漏条。
                if (local && remoteMode) {
                    adapter.setFooterState(hasMore
                            ? PoemCardAdapter.FOOTER_NONE : PoemCardAdapter.FOOTER_END);
                    return;
                }
                // 翻到第几页就以哪一层为准：本地翻着翻着远端第一页到了，
                // remoteMode 会被置上，但后续这几页仍是本地给的，不能中途换游标
                if (page == nextPage) {
                    adapter.append(result.getItems());
                    nextPage = result.getPage() + 1;
                    hasMore = result.hasMore();
                    pagedBeyondFirst = true;
                }
                adapter.setFooterState(hasMore
                        ? PoemCardAdapter.FOOTER_NONE : PoemCardAdapter.FOOTER_END);
            }

            @Override
            public void onError(@NonNull Throwable error) {
                if (binding == null) {
                    return;
                }
                // 取下一页失败不该动已经显示的内容，收掉底部的「加载中」就行
                loadingMore = false;
                hasMore = false;
                adapter.setFooterState(PoemCardAdapter.FOOTER_END);
            }
        };
        if (remoteMode) {
            repository.listRemote(query, page, PAGE_SIZE, callback);
        } else {
            repository.listLocal(query, page, PAGE_SIZE, callback);
        }
    }

    @Override
    protected void onDestroy() {
        // 摘订阅放在 super 之前：清单里的那次回调不该打在一个正要销毁的 Activity 上。
        if (readyWatcher != null) {
            repository.removeDbStatusListener(readyWatcher);
            readyWatcher = null;
        }
        super.onDestroy();
    }
}
