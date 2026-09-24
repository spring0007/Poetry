package com.example.poetry.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.poetry.DetailActivity;
import com.example.poetry.R;
import com.example.poetry.adapter.PoemCardAdapter;
import com.example.poetry.data.DbStatus;
import com.example.poetry.data.DbStatusListener;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.PoemKind;
import com.example.poetry.databinding.FragmentDiscoverBinding;
import com.example.poetry.util.Chips;
import com.google.android.material.chip.Chip;

import java.util.Calendar;
import java.util.List;

/**
 * 发现页：Hero 今日推荐 + 体裁快捷筛选 + 精选诗词列表 + 空态。
 */
public class DiscoverFragment extends Fragment {

    private FragmentDiscoverBinding binding;
    private PoetryRepository repository;
    private PoemCardAdapter adapter;

    /**
     * 诗库状态订阅者，**每个视图一份**，在 {@code onViewCreated} 里新建。
     *
     * <p>不复用同一个实例：{@link com.example.poetry.data.DbStatusListener.ReadyWatcher}
     * 靠「记住上一次的值」来判断跃迁，而它的第一次回调无论如何都算跃迁。转屏之后重新注册时，
     * 若还是旧实例，它记得上一次已经是 ready，就会**跳过初始加载**，页面停在空白。
     */
    private DbStatusListener readyWatcher;

    /** 当前体裁筛选；空串表示「全部」 */
    private String currentKind = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDiscoverBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repository = PoetryRepository.get(requireContext());

        setupChips();
        setupList();

        // 订阅诗库状态。注册会立刻回调一次当前状态，ReadyWatcher 把第一次回调算作跃迁，
        // 所以下面不需要再单独调一次 loadAll()——首次加载就是这次回调做的。
        // 库是下载下来之后才可用的，装好那一刻收到跃迁，页面才会从 15 首示例变成真实数据。
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

    /** 一次把这一页要的数据都取回来。库换了之后整页重取。 */
    private void loadAll() {
        loadHero();
        loadFeatured();
    }

    // ---------------------------------------------------------------- 初始化

    private void setupChips() {
        binding.kindChips.removeAllViews();
        Chip all = Chips.create(requireContext(), getString(R.string.category_all), false);
        all.setChecked(true);
        all.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                currentKind = "";
                loadFeatured();
            }
        });
        binding.kindChips.addView(all);

        for (PoemKind kind : PoemKind.values()) {
            Chip chip = Chips.create(requireContext(), kind.getLabel(), false);
            chip.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    currentKind = kind.getCode();
                    loadFeatured();
                }
            });
            binding.kindChips.addView(chip);
        }
    }

    private void setupList() {
        adapter = new PoemCardAdapter(new PoemCardAdapter.Listener() {
            @Override
            public void onPoemClick(@NonNull Poem poem) {
                DetailActivity.open(requireContext(), poem);
            }

            @Override
            public void onFavoriteClick(@NonNull Poem poem) {
                boolean added = repository.toggleFavorite(poem);
                Toast.makeText(requireContext(),
                        added ? R.string.shelf_added : R.string.shelf_removed,
                        Toast.LENGTH_SHORT).show();
            }
        });
        binding.poemList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.poemList.setAdapter(adapter);
        binding.poemList.setNestedScrollingEnabled(false);

        binding.featuredHeader.sectionTitle.setText(R.string.section_selected);
    }

    // ---------------------------------------------------------------- 数据

    private void loadHero() {
        repository.daily(new com.example.poetry.data.Callback<Poem>() {
            @Override
            public void onData(@NonNull Poem poem) {
                if (binding == null) {
                    return;
                }
                binding.heroCard.heroTag.setText(getString(R.string.hero_tag_prefix) + seasonName());
                binding.heroCard.heroTitle.setText(poem.getTitle());
                binding.heroCard.heroAuthor.setText(poem.getAuthorLabel());
                binding.heroCard.heroLine.setText(poem.getExcerpt());
                binding.heroCard.heroCta.setOnClickListener(
                        v -> DetailActivity.open(requireContext(), poem));
            }

            @Override
            public void onError(@Nullable Throwable error) {
                if (binding != null) {
                    binding.heroCard.getRoot().setVisibility(View.GONE);
                }
            }
        });
    }

    private void loadFeatured() {
        binding.loadingBar.setVisibility(View.VISIBLE);
        binding.emptyState.getRoot().setVisibility(View.GONE);

        com.example.poetry.data.Callback<List<Poem>> callback =
                new com.example.poetry.data.Callback<List<Poem>>() {
                    @Override
                    public void onData(@NonNull List<Poem> poems) {
                        if (binding == null) {
                            return;
                        }
                        binding.loadingBar.setVisibility(View.GONE);
                        adapter.submit(poems);
                        binding.featuredHeader.sectionMeta.setText(
                                getString(R.string.count_poems, poems.size()));
                        boolean empty = poems.isEmpty();
                        binding.emptyState.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
                        binding.poemList.setVisibility(empty ? View.GONE : View.VISIBLE);
                        if (empty) {
                            binding.emptyState.emptyTitle.setText(R.string.empty_title);
                            binding.emptyState.emptyDesc.setText(R.string.empty_desc);
                        }
                    }

                    @Override
                    public void onError(@Nullable Throwable error) {
                        if (binding == null) {
                            return;
                        }
                        binding.loadingBar.setVisibility(View.GONE);
                        binding.emptyState.getRoot().setVisibility(View.VISIBLE);
                        binding.emptyState.emptyTitle.setText(R.string.empty_title);
                        binding.emptyState.emptyDesc.setText(R.string.empty_desc);
                        binding.poemList.setVisibility(View.GONE);
                    }
                };

        if (currentKind.isEmpty()) {
            repository.featured(20, callback);
        } else {
            repository.listByKind(currentKind, 20, callback);
        }
    }

    @NonNull
    private String seasonName() {
        int month = Calendar.getInstance().get(Calendar.MONTH) + 1;
        if (month >= 3 && month <= 5) {
            return "春";
        }
        if (month >= 6 && month <= 8) {
            return "夏";
        }
        if (month >= 9 && month <= 11) {
            return "秋";
        }
        return "冬";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 先摘订阅再把 binding 置空：晚一步的话，已经在队列里的那一次回调会撞上 null binding。
        if (readyWatcher != null) {
            repository.removeDbStatusListener(readyWatcher);
            readyWatcher = null;
        }
        binding = null;
    }
}
