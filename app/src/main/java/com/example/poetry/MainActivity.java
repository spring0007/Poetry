package com.example.poetry;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;

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

import java.util.List;

/**
 * 主壳层：常驻顶部搜索栏 + 搜索面板 + 四个页面 + 底部导航。
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private PoetryRepository repository;
    private UserStore store;
    private SearchResultAdapter searchAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 当前已套用的皮肤；换肤后回到本页时据此重建 */
    private String appliedSkin;
    private Runnable searchRunnable;

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
        searchAdapter = new SearchResultAdapter(poem -> {
            DetailActivity.open(this, poem);
            closeSearchPanel();
        });
        binding.searchResults.setLayoutManager(new LinearLayoutManager(this));
        binding.searchResults.setAdapter(searchAdapter);

        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // ignore
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.searchClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                scheduleSearch(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
                // ignore
            }
        });

        binding.searchInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                openSearchPanel();
            }
        });

        binding.searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(binding.searchInput.getText().toString());
                return true;
            }
            return false;
        });

        binding.searchClear.setOnClickListener(v -> {
            binding.searchInput.setText("");
            searchAdapter.submit(new java.util.ArrayList<>());
            binding.searchEmpty.setVisibility(View.GONE);
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

    private void scheduleSearch(@NonNull String keyword) {
        if (searchRunnable != null) {
            handler.removeCallbacks(searchRunnable);
        }
        searchRunnable = () -> runSearch(keyword);
        // 300ms 防抖，避免每输入一个字就扫一次全库
        handler.postDelayed(searchRunnable, 300);
    }

    private void runSearch(@NonNull String keyword) {
        if (keyword.trim().isEmpty()) {
            searchAdapter.submit(new java.util.ArrayList<>());
            binding.searchEmpty.setVisibility(View.GONE);
            return;
        }
        // 搜索面板只展示少量命中，避免面板顶满屏幕
        repository.search(keyword, 8, new Callback<List<Poem>>() {
            @Override
            public void onData(@NonNull List<Poem> poems) {
                if (binding == null) {
                    return;
                }
                searchAdapter.submit(poems);
                binding.searchEmpty.setVisibility(poems.isEmpty() ? View.VISIBLE : View.GONE);
                binding.panelTitle.setText(getString(R.string.search_result_count, poems.size()));
            }

            @Override
            public void onError(@Nullable Throwable error) {
                if (binding != null) {
                    binding.searchEmpty.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void openSearchPanel() {
        binding.searchPanel.setVisibility(View.VISIBLE);
        searchBackCallback.setEnabled(true);
    }

    private void closeSearchPanel() {
        binding.searchPanel.setVisibility(View.GONE);
        binding.searchInput.clearFocus();
        searchBackCallback.setEnabled(false);
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
        binding = null;
    }
}
