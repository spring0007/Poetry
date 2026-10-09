package com.example.poetry.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.poetry.AuthorDetailActivity;
import com.example.poetry.PoemListActivity;
import com.example.poetry.R;
import com.example.poetry.adapter.AuthorAdapter;
import com.example.poetry.adapter.CategoryAdapter;
import com.example.poetry.adapter.DynastyAdapter;
import com.example.poetry.data.Callback;
import com.example.poetry.data.DbStatus;
import com.example.poetry.data.DbStatusListener;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.model.Author;
import com.example.poetry.data.model.Category;
import com.example.poetry.databinding.FragmentCategoryBinding;
import com.example.poetry.util.Chips;
import com.google.android.material.chip.Chip;

import java.util.List;

/**
 * 分类页：按体裁 / 按朝代 / 按主题 / 热门作者。
 */
public class CategoryFragment extends Fragment {

    private FragmentCategoryBinding binding;
    private PoetryRepository repository;

    private CategoryAdapter typeAdapter;
    private DynastyAdapter dynastyAdapter;
    private AuthorAdapter authorAdapter;

    /**
     * 诗库状态订阅者，**每个视图一份**，在 {@code onViewCreated} 里新建。
     *
     * <p>不复用同一个实例：{@link DbStatusListener.ReadyWatcher} 靠记住上一次的值判断跃迁，
     * 第一次回调一定算跃迁。转屏后若还沿用旧实例，它会记得上次已经 ready 而跳过初始加载。
     */
    private DbStatusListener readyWatcher;

    // 四个板块「已经交过货」的位掩码（成功或失败都算，见 settled）。
    private static final int SECTION_TYPES = 1;
    private static final int SECTION_DYNASTIES = 1 << 1;
    private static final int SECTION_THEMES = 1 << 2;
    private static final int SECTION_AUTHORS = 1 << 3;
    private static final int SECTION_ALL = SECTION_TYPES | SECTION_DYNASTIES
            | SECTION_THEMES | SECTION_AUTHORS;

    /** 本轮 loadAll 里已经交过货的板块；四块齐了才收掉加载圈 */
    private int settled;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCategoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repository = PoetryRepository.get(requireContext());

        // 适配器与监听器只装一次，之后不再碰。
        bindTypes();
        bindDynasties();
        bindThemes();
        bindAuthors();

        // 订阅诗库状态：注册会立刻回调一次，ReadyWatcher 把第一次算作跃迁，
        // 所以首次加载（loadAll）由这次回调完成，这里不必再单独调一遍。
        readyWatcher = new DbStatusListener.ReadyWatcher() {
            @Override
            protected void onReadyChanged(boolean ready, @NonNull DbStatus status) {
                if (binding == null) {
                    return;
                }
                loadAll();
            }
        };
        repository.addDbStatusListener(readyWatcher);
    }

    /** 一次取回这一页四块数据。库换了之后整页重取。 */
    private void loadAll() {
        settled = 0;
        binding.categoryLoading.setVisibility(View.VISIBLE);
        loadTypes();
        loadDynasties();
        loadThemes();
        loadAuthors();
    }

    /**
     * 某个板块交过货了（拿到数据或彻底失败，都算）。四块齐了才收掉加载圈
     * —— 半屏宫格配着一个转不停的圈，更容易被当成卡死。
     *
     * <p>按板块**取或**而不是计数：{@code localFirst} 那一层本地先给一次、
     * 远端回来还会再给一次（见 {@code PoetryRepository.localFirst}），数次数会提前收圈。
     */
    private void settle(int section) {
        settled |= section;
        if (settled == SECTION_ALL && binding != null) {
            binding.categoryLoading.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- 按体裁

    /** 只搭架子：表头、适配器、布局。 */
    private void bindTypes() {
        binding.typeHeader.sectionTitle.setText(R.string.section_by_type);
        typeAdapter = new CategoryAdapter(category ->
                PoemListActivity.open(requireContext(), category.getName(),
                        PoemListActivity.MODE_KIND, category.getCode()));
        binding.typeList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.typeList.setAdapter(typeAdapter);
        binding.typeList.setNestedScrollingEnabled(false);
    }

    /** 可安全重入：{@code submit} 是替换语义。 */
    private void loadTypes() {
        repository.kindCategories(new Callback<List<Category>>() {
            @Override
            public void onData(@NonNull List<Category> categories) {
                if (binding == null) {
                    return;
                }
                settle(SECTION_TYPES);
                typeAdapter.submit(categories);
                long total = 0;
                for (Category category : categories) {
                    total += category.getCount();
                }
                binding.typeHeader.sectionMeta.setText(categories.size() + " 种 · " + total + " 篇");
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // 保留空列表即可
                settle(SECTION_TYPES);
            }
        });
    }

    // ---------------------------------------------------------------- 按朝代

    private void bindDynasties() {
        binding.dynastyHeader.sectionTitle.setText(R.string.section_by_dynasty);
        dynastyAdapter = new DynastyAdapter(category ->
                PoemListActivity.open(requireContext(), category.getName() + "代作品",
                        PoemListActivity.MODE_DYNASTY, category.getCode()));
        binding.dynastyList.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.dynastyList.setAdapter(dynastyAdapter);
        binding.dynastyList.setNestedScrollingEnabled(false);
    }

    private void loadDynasties() {
        repository.dynastyCategories(new Callback<List<Category>>() {
            @Override
            public void onData(@NonNull List<Category> categories) {
                if (binding == null) {
                    return;
                }
                settle(SECTION_DYNASTIES);
                dynastyAdapter.submit(categories);
                binding.dynastyHeader.sectionMeta.setText(categories.size() + " 个");
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // ignore
                settle(SECTION_DYNASTIES);
            }
        });
    }

    // ---------------------------------------------------------------- 按主题

    private void bindThemes() {
        binding.themeHeader.sectionTitle.setText(R.string.section_by_theme);
    }

    /**
     * Chip 是**每次加载都重建**的（不像 RecyclerView 有 submit 那样的替换语义）。
     * 开头的 {@code removeAllViews()} 必须留着，否则库热重载后重跑一遍，这里就会叠出
     * 12 个重复 chip，而且老的还带着指向旧数据的监听器。
     */
    private void loadThemes() {
        binding.themeChips.removeAllViews();
        repository.themeCategories(new Callback<List<Category>>() {
            @Override
            public void onData(@NonNull List<Category> categories) {
                if (binding == null) {
                    return;
                }
                settle(SECTION_THEMES);
                for (Category category : categories) {
                    Chip chip = Chips.create(requireContext(),
                            category.getName() + " · " + category.getCount(), true);
                    chip.setOnClickListener(v -> PoemListActivity.open(requireContext(),
                            category.getName(), PoemListActivity.MODE_THEME, category.getCode()));
                    binding.themeChips.addView(chip);
                }
                binding.themeHeader.sectionMeta.setText(categories.size() + " 个");
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // ignore
                settle(SECTION_THEMES);
            }
        });
    }

    // ---------------------------------------------------------------- 热门作者

    private void bindAuthors() {
        binding.authorHeader.sectionTitle.setText(R.string.section_by_author);
        binding.authorHeader.sectionMeta.setText(R.string.section_by_author_meta);
        // 热门作者 → 作者详情页（简介 + 代表作品），与作品详情页的导航方式一致
        authorAdapter = new AuthorAdapter(author ->
                AuthorDetailActivity.open(requireContext(), author));
        binding.authorList.setLayoutManager(new LinearLayoutManager(requireContext(),
                LinearLayoutManager.HORIZONTAL, false));
        binding.authorList.setAdapter(authorAdapter);
        binding.authorList.setNestedScrollingEnabled(false);
    }

    private void loadAuthors() {
        repository.topAuthors(12, new Callback<List<Author>>() {
            @Override
            public void onData(@NonNull List<Author> authors) {
                if (binding == null) {
                    return;
                }
                settle(SECTION_AUTHORS);
                authorAdapter.submit(authors);
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // ignore
                settle(SECTION_AUTHORS);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 先摘订阅再把 binding 置空：晚一步的话，队列里的那次回调会撞上 null binding。
        if (readyWatcher != null) {
            repository.removeDbStatusListener(readyWatcher);
            readyWatcher = null;
        }
        binding = null;
    }
}
