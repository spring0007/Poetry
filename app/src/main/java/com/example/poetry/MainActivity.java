package com.example.poetry;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.poetry.adapter.SearchResultAdapter;
import com.example.poetry.data.Callback;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ActivityMainBinding;
import com.example.poetry.fragment.BookshelfFragment;
import com.example.poetry.fragment.CategoryFragment;
import com.example.poetry.fragment.DiscoverFragment;
import com.example.poetry.fragment.MineFragment;
import com.example.poetry.ui.Skin;
import com.example.poetry.util.Chips;

import java.util.ArrayList;
import java.util.List;

/**
 * 主壳层：常驻顶部搜索栏 + 搜索面板 + 四个页面 + 底部导航。
 */
public class MainActivity extends AppCompatActivity {

    /** 面板只预览这么多条命中，更多交给「查看全部」的整页列表 */
    private static final int SEARCH_PREVIEW_LIMIT = 8;

    private ActivityMainBinding binding;
    private PoetryRepository repository;
    private UserStore store;
    private SearchResultAdapter searchAdapter;
    private InputMethodManager imm;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 当前已套用的皮肤；换肤后回到本页时据此重建 */
    private String appliedSkin;
    private Runnable searchRunnable;
    /** 请求序号：只有最后一次请求的响应能上屏，慢回来的旧响应直接丢掉 */
    private long searchSeq;
    /** 面板里这批结果对应的关键词，点「查看全部」时带过去 */
    private String lastKeyword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = PoetryRepository.get(this);
        store = repository.store();
        appliedSkin = store.getSkinId();

        // 适配 edge-to-edge 显示，避免底部导航被系统栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        binding.bottomNav.setOnItemSelectedListener(item -> {
            // 正在搜索时切页签：先把面板收掉，否则新页面被面板挡在后面看不见
            closeSearchPanel();
            switchFragment(item.getItemId());
            return true;
        });

        setupSearch();
        setupThemeButton();
        // 只注册一次；生命周期结束时由 dispatcher 自动摘除
        getOnBackPressedDispatcher().addCallback(this, searchBackCallback);

        // 仅在首次创建时选中默认页签，重建时由系统恢复状态
        if (savedInstanceState == null) {
            binding.bottomNav.setSelectedItemId(R.id.nav_discover);
        }
    }

    /**
     * 根据底部导航项切换对应的 Fragment
     */
    private void switchFragment(int itemId) {
        Fragment fragment;
        if (itemId == R.id.nav_category) {
            fragment = new CategoryFragment();
        } else if (itemId == R.id.nav_bookshelf) {
            fragment = new BookshelfFragment();
        } else if (itemId == R.id.nav_mine) {
            fragment = new MineFragment();
        } else {
            fragment = new DiscoverFragment();
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 在皮肤页换了配色：回来后要重建本页才会生效
        if (store != null && appliedSkin != null
                && !appliedSkin.equals(store.getSkinId())) {
            recreate();
        }
    }

    // ---------------------------------------------------------------- 搜索

    private void setupSearch() {
        imm = getSystemService(InputMethodManager.class);
        searchAdapter = new SearchResultAdapter(poem -> {
            // 带上整份搜索结果：详情页里上下滑动就沿这次搜索的结果翻
            DetailActivity.openInList(this, searchAdapter.items(), poem);
            closeSearchPanel();
        });
        binding.searchResults.setLayoutManager(new LinearLayoutManager(this));
        binding.searchResults.setAdapter(searchAdapter);

        // 放大镜和左右留白也要能点开搜索：EditText 自己只吃那一行文字的区域
        binding.searchField.setOnClickListener(v -> focusSearchInput());
        binding.panelCancel.setOnClickListener(v -> closeSearchPanel());
        binding.searchAllRow.setOnClickListener(v -> openAllResults());

        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // ignore
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (binding == null) {
                    return;
                }
                binding.searchClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                scheduleSearch(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
                // ignore
            }
        });

        // 面板只在获得焦点时展开（点搜索框、点热词都会走到这里）
        binding.searchInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                openSearchPanel();
            }
        });

        binding.searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // 键盘上按了「搜索」：立刻出结果，不再等剩下的防抖时间
                cancelPendingSearch();
                runSearch(binding.searchInput.getText().toString());
                return true;
            }
            return false;
        });

        binding.searchClear.setOnClickListener(v -> {
            binding.searchInput.setText("");
            showHotKeywords();
        });

        repository.hotKeywords(new Callback<List<String>>() {
            @Override
            public void onData(@NonNull List<String> words) {
                if (binding == null) {
                    return;
                }
                binding.hotChips.removeAllViews();
                for (String word : words) {
                    com.google.android.material.chip.Chip chip =
                            Chips.create(MainActivity.this, word, true);
                    chip.setOnClickListener(v -> {
                        binding.searchInput.setText(word);
                        binding.searchInput.setSelection(word.length());
                        // setText 已经排了一次防抖，这里别再查第二遍
                        cancelPendingSearch();
                        runSearch(word);
                    });
                    binding.hotChips.addView(chip);
                }
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // ignore
            }
        });
    }

    /** 点搜索框这条路径：聚焦 + 展开面板 + 弹键盘，三件事一起做 */
    private void focusSearchInput() {
        if (binding == null) {
            return;
        }
        binding.searchInput.requestFocus();
        openSearchPanel();
        showIme();
    }

    /** 300ms 防抖，避免每输入一个字就扫一次全库 */
    private void scheduleSearch(@NonNull String keyword) {
        cancelPendingSearch();
        searchRunnable = () -> runSearch(keyword);
        handler.postDelayed(searchRunnable, 300);
    }

    /** 关键词已经定下来（回车、点热词、关面板）时，把还没跑到的防抖撤掉 */
    private void cancelPendingSearch() {
        if (searchRunnable != null) {
            handler.removeCallbacks(searchRunnable);
            searchRunnable = null;
        }
    }

    private void runSearch(@NonNull String keyword) {
        if (binding == null) {
            return;
        }
        final String query = keyword.trim();
        if (query.isEmpty()) {
            showHotKeywords();
            return;
        }
        // 序号防串台：输入「李白」时「李」的响应可能后到，只认最后一次请求
        final long seq = ++searchSeq;
        repository.search(query, SEARCH_PREVIEW_LIMIT, new Callback<List<Poem>>() {
            @Override
            public void onData(@NonNull List<Poem> poems) {
                if (binding == null || seq != searchSeq) {
                    return;
                }
                lastKeyword = query;
                boolean empty = poems.isEmpty();
                searchAdapter.submit(poems);
                binding.panelTitle.setText(getString(R.string.search_result_count, poems.size()));
                binding.hotChips.setVisibility(View.GONE);
                binding.searchResults.setVisibility(empty ? View.GONE : View.VISIBLE);
                binding.searchEmpty.setText(R.string.search_no_match);
                binding.searchEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                // 只有拿满了预览条数才谈得上「还有更多」：3 条结果进整页还是那 3 条
                binding.searchAllRow.setVisibility(
                        poems.size() >= SEARCH_PREVIEW_LIMIT ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(@Nullable Throwable error) {
                if (binding == null || seq != searchSeq) {
                    return;
                }
                // 诗库没就绪导致的失败，跟「没有匹配」是两回事，别让用户以为是自己搜错了
                searchAdapter.submit(new ArrayList<>());
                binding.panelTitle.setText(R.string.search_failed);
                binding.hotChips.setVisibility(View.GONE);
                binding.searchResults.setVisibility(View.GONE);
                binding.searchAllRow.setVisibility(View.GONE);
                binding.searchEmpty.setText(R.string.search_failed_hint);
                binding.searchEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    /** 输入为空时的面板：摆热门搜索词，结果区和「查看全部」都收起来 */
    private void showHotKeywords() {
        if (binding == null) {
            return;
        }
        lastKeyword = null;
        searchAdapter.submit(new ArrayList<>());
        binding.panelTitle.setText(R.string.hot_search);
        binding.hotChips.setVisibility(View.VISIBLE);
        binding.searchResults.setVisibility(View.GONE);
        binding.searchAllRow.setVisibility(View.GONE);
        binding.searchEmpty.setVisibility(View.GONE);
    }

    /** 面板只给 8 条预览，整页结果交给 PoemListActivity（同一个查询，条数放到 60） */
    private void openAllResults() {
        if (lastKeyword == null) {
            return;
        }
        PoemListActivity.open(this, getString(R.string.search_all_title, lastKeyword),
                PoemListActivity.MODE_SEARCH, lastKeyword);
        closeSearchPanel();
    }

    private void openSearchPanel() {
        boolean alreadyOpen = binding.searchPanel.getVisibility() == View.VISIBLE;
        binding.searchPanel.setVisibility(View.VISIBLE);
        // 面板独占内容区：让它按内容撑高会把诗库列表挤成 0 高，结果也滚不动
        binding.fragmentContainer.setVisibility(View.GONE);
        searchBackCallback.setEnabled(true);
        // 点搜索框和焦点回调会各调一次本方法；关键词没变就别再查第二遍
        String keyword = binding.searchInput.getText().toString().trim();
        if (alreadyOpen && keyword.equals(lastKeyword == null ? "" : lastKeyword)) {
            return;
        }
        // 按当前输入重算一遍：面板关着的时候诗库可能刚重载过
        if (keyword.isEmpty()) {
            showHotKeywords();
        } else {
            runSearch(keyword);
        }
    }

    private void closeSearchPanel() {
        binding.searchPanel.setVisibility(View.GONE);
        binding.fragmentContainer.setVisibility(View.VISIBLE);
        binding.searchInput.clearFocus();
        hideIme();
        cancelPendingSearch();
        searchBackCallback.setEnabled(false);
    }

    private void showIme() {
        if (imm != null) {
            imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /** 用 searchInput 自己的窗口 token 收键盘：此刻它已经 clearFocus，getCurrentFocus() 拿不到 */
    private void hideIme() {
        if (imm == null || binding == null) {
            return;
        }
        IBinder token = binding.searchInput.getWindowToken();
        if (token != null) {
            imm.hideSoftInputFromWindow(token, 0);
        }
    }

    // ---------------------------------------------------------------- 主题

    private void setupThemeButton() {
        binding.themeButton.setOnClickListener(v -> {
            boolean night = store.getNightMode() == UserStore.NIGHT_ON;
            store.setNightMode(night ? UserStore.NIGHT_OFF : UserStore.NIGHT_ON);
            AppCompatDelegate.setDefaultNightMode(night
                    ? AppCompatDelegate.MODE_NIGHT_NO
                    : AppCompatDelegate.MODE_NIGHT_YES);
        });
    }

    /** 搜索面板展开时才接管返回键；收起后立刻交还给系统，否则返回键会永远退不出 App。 */
    private final OnBackPressedCallback searchBackCallback =
            new OnBackPressedCallback(false) {
                @Override
                public void handleOnBackPressed() {
                    closeSearchPanel();
                }
            };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 防抖任务还挂在主线程队列上：销毁后才触发会走到空的 binding
        cancelPendingSearch();
        binding = null;
    }
}
