package com.example.poetry.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.poetry.DetailActivity;
import com.example.poetry.R;
import com.example.poetry.adapter.PoemCardAdapter;
import com.example.poetry.adapter.PoemRowAdapter;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.PoemKind;
import com.example.poetry.databinding.FragmentBookshelfBinding;
import com.example.poetry.util.Chips;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 书架页：统计概览 + 继续朗读 + 视图（网格 / 列表）与排序切换 + 收藏列表 + 空态。
 */
public class BookshelfFragment extends Fragment {

    private FragmentBookshelfBinding binding;
    private PoetryRepository repository;
    private UserStore store;

    private PoemCardAdapter gridAdapter;
    private PoemRowAdapter listAdapter;

    /** true = 网格视图 */
    private boolean gridMode = true;
    /** 排序：0 最近 / 1 体裁 / 2 热度 */
    private int sortMode = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentBookshelfBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repository = PoetryRepository.get(requireContext());
        store = repository.store();

        setupAdapters();
        setupSortChips();
        setupViewToggle();

        binding.emptyState.emptyTitle.setText(R.string.shelf_empty_title);
        binding.emptyState.emptyDesc.setText(R.string.shelf_empty_desc);
        binding.emptyState.emptyAction.setText(R.string.shelf_empty_action);
        binding.emptyState.emptyAction.setVisibility(View.VISIBLE);
        binding.emptyState.emptyAction.setOnClickListener(v -> binding.resumeCard.performClick());

        binding.resumeCard.setOnClickListener(v -> {
            List<Poem> favorites = favorites();
            if (favorites.isEmpty()) {
                Toast.makeText(requireContext(), R.string.shelf_empty_toast, Toast.LENGTH_SHORT).show();
            } else {
                DetailActivity.open(requireContext(), favorites.get(0));
            }
        });

        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 详情页收藏变化后回到书架需要刷新
        if (binding != null) {
            refresh();
        }
    }

    // ---------------------------------------------------------------- 初始化

    private void setupAdapters() {
        gridAdapter = new PoemCardAdapter(new PoemCardAdapter.Listener() {
            @Override
            public void onPoemClick(@NonNull Poem poem) {
                DetailActivity.open(requireContext(), poem);
            }

            @Override
            public void onFavoriteClick(@NonNull Poem poem) {
                removePoem(poem);
            }
        });
        listAdapter = new PoemRowAdapter(new PoemRowAdapter.Listener() {
            @Override
            public void onPoemClick(@NonNull Poem poem) {
                DetailActivity.open(requireContext(), poem);
            }

            @Override
            public void onRemoveClick(@NonNull Poem poem) {
                removePoem(poem);
            }
        });
    }

    private void setupSortChips() {
        binding.sortChips.removeAllViews();
        String[] labels = {
                getString(R.string.shelf_sort_recent),
                getString(R.string.shelf_sort_type),
                getString(R.string.shelf_sort_hot)
        };
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Chip chip = Chips.create(requireContext(), labels[i], false);
            chip.setChecked(i == sortMode);
            chip.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    sortMode = index;
                    refresh();
                }
            });
            binding.sortChips.addView(chip);
        }
    }

    private void setupViewToggle() {
        updateToggleState();
        binding.btnGrid.setOnClickListener(v -> {
            gridMode = true;
            updateToggleState();
            refresh();
        });
        binding.btnList.setOnClickListener(v -> {
            gridMode = false;
            updateToggleState();
            refresh();
        });
    }

    private void updateToggleState() {
        binding.btnGrid.setSelected(gridMode);
        binding.btnList.setSelected(!gridMode);
        binding.btnGrid.setAlpha(gridMode ? 1f : 0.55f);
        binding.btnList.setAlpha(gridMode ? 0.55f : 1f);
    }

    // ---------------------------------------------------------------- 数据

    private void removePoem(@NonNull Poem poem) {
        repository.removeFavorite(poem.getId());
        Toast.makeText(requireContext(), R.string.shelf_removed, Toast.LENGTH_SHORT).show();
        refresh();
    }

    @NonNull
    private List<Poem> favorites() {
        List<Poem> list = new ArrayList<>(repository.favorites());
        if (sortMode == 1) {
            Collections.sort(list, (a, b) -> a.getKind().getCode().compareTo(b.getKind().getCode()));
        } else if (sortMode == 2) {
            Collections.sort(list, (a, b) -> b.getScore() - a.getScore());
        }
        return list;
    }

    private void refresh() {
        List<Poem> list = favorites();
        boolean empty = list.isEmpty();

        binding.shelfSubtitle.setText(getString(R.string.shelf_subtitle, list.size()));
        binding.statFav.setText(String.valueOf(list.size()));

        Set<String> kinds = new HashSet<>();
        for (Poem poem : list) {
            kinds.add(poem.getKind().getLabel());
        }
        binding.statType.setText(String.valueOf(kinds.size()));
        binding.statHours.setText(String.format(java.util.Locale.CHINA, "%.1f", store.getReadHours()));

        binding.emptyState.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.shelfList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.resumeCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.shelfFooter.setText(getString(R.string.shelf_footer, list.size()));

        if (!empty) {
            Poem last = list.get(0);
            binding.resumeTitle.setText(getString(R.string.shelf_resume, last.getTitle()));
            int percent = store.getProgress(last.getId());
            if (percent <= 0) {
                percent = 35;
            }
            binding.resumeSub.setText(getString(R.string.shelf_resume_sub,
                    last.getAuthorName(), PoemKind.fromSource(last.getSrcName()).getLabel(), percent));
        }

        if (gridMode) {
            binding.shelfList.setLayoutManager(new GridLayoutManager(requireContext(), 2));
            gridAdapter.submit(list);
            binding.shelfList.setAdapter(gridAdapter);
        } else {
            binding.shelfList.setLayoutManager(new LinearLayoutManager(requireContext()));
            listAdapter.submit(list);
            binding.shelfList.setAdapter(listAdapter);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
