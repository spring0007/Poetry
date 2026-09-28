package com.example.poetry.fragment;

import android.text.Editable;
import android.text.TextWatcher;
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

import com.example.poetry.HistoryActivity;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 书架页：统计概览 + 继续朗读 + 视图（网格 / 列表）与排序切换 + 收藏列表 + 空态。
 * <p>
 * 打开书架默认就是整理态：网格卡右上角、列表行右侧直接带 ✕，点 ✕ 弹二次确认后才真正
 * 移出，移出后立刻重刷列表，并同步本地收藏状态（详情页的 ♡ 也会跟着变）。
 * 顶部只有「完成」与「清空书架」两个按钮：「完成」收起全部 ✕，长按任意卡片可重新展开。
 */
public class BookshelfFragment extends Fragment {

    private FragmentBookshelfBinding binding;
    private PoetryRepository repository;
    private UserStore store;

    private PoemCardAdapter gridAdapter;
    private PoemRowAdapter listAdapter;

    /** true = 网格视图 */
    private boolean gridMode = true;
    /** true = 整理态（卡片上直接显示 ✕）；默认打开，点「完成」收起 */
    private boolean editMode = true;
    /** 书架内搜索关键字，空串表示不过滤 */
    private String query = "";

    /** 排序：0 最近 / 1 体裁 / 2 热度 */
    private int sortMode = 0;

    /**
     * 收藏变更监听：详情页收藏/取消后，书架页无需等到 onResume 才刷新。
     * 与 DetailActivity 走的是同一份 {@link UserStore} 单例。
     */
    private final UserStore.FavoriteListener favoriteListener = new UserStore.FavoriteListener() {
        @Override
        public void onFavoritesChanged() {
            // isAdded() 不能省：回调可能在 Fragment 已 detach 但 binding 还没置空时到达
            if (binding != null && isAdded()) {
                refresh();
            }
        }
    };

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

        gridMode = store.isShelfGrid();
        sortMode = store.getShelfSort();
        setupAdapters();
        setupSortChips();
        setupViewToggle();
        setupSearch();

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

        store.addFavoriteListener(favoriteListener);
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

    @Override
    public void onDestroyView() {
        store.removeFavoriteListener(favoriteListener);
        super.onDestroyView();
        binding = null;
    }

    // ---------------------------------------------------------------- 初始化

    private void setupAdapters() {
        gridAdapter = new PoemCardAdapter(new PoemCardAdapter.Listener() {
            @Override
            public void onPoemClick(@NonNull Poem poem) {
                // 整理态下卡片照常可点开：删除只认卡片上的 ✕，点正文不会误删也不会白点
                DetailActivity.open(requireContext(), poem);
            }

            @Override
            public void onFavoriteClick(@NonNull Poem poem) {
                confirmRemove(poem);
            }
        });
        gridAdapter.setDeleteListener(new PoemCardAdapter.DeleteListener() {
            @Override
            public void onDeleteClick(@NonNull Poem poem) {
                confirmRemove(poem);
            }

            @Override
            public void onLongPress(@NonNull Poem poem) {
                setEditMode(true);
            }
        });
        listAdapter = new PoemRowAdapter(new PoemRowAdapter.Listener() {
            @Override
            public void onPoemClick(@NonNull Poem poem) {
                // 同上：列表行的 ✕ 才是删除入口，点行本身是看详情
                DetailActivity.open(requireContext(), poem);
            }

            @Override
            public void onRemoveClick(@NonNull Poem poem) {
                confirmRemove(poem);
            }
        });
        listAdapter.setEditListener(poem -> setEditMode(true));
    }

    /** 搜索框、清空书架，以及列表视图上的收藏时间 */
    private void setupSearch() {
        listAdapter.setMetaProvider(this::favoriteMeta);
        binding.shelfSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // ignore
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString();
                refresh();
            }

            @Override
            public void afterTextChanged(Editable s) {
                // ignore
            }
        });
        binding.btnClear.setOnClickListener(v -> confirmClear());
    }

    /** 清空前先确认一次：收藏没有云端备份，删掉就找不回来了 */
    private void confirmClear() {
        if (repository.favorites().isEmpty()) {
            Toast.makeText(requireContext(), R.string.shelf_empty_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.shelf_clear)
                .setMessage(R.string.shelf_clear_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    store.clearFavorites();
                    setEditMode(false);
                    Toast.makeText(requireContext(), R.string.shelf_cleared, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    /**
     * 单首移出前的二次确认。
     *
     * <p>收藏只存在本机、没有云端备份，删掉就找不回来，所以这里必须拦一道；
     * 确认后才写本地数据并重刷列表。
     */
    private void confirmRemove(@NonNull Poem poem) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.shelf_remove_title)
                .setMessage(getString(R.string.shelf_remove_confirm, poem.getTitle()))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    repository.removeFavorite(poem.getId());
                    Toast.makeText(requireContext(), R.string.shelf_removed, Toast.LENGTH_SHORT).show();
                    refresh();
                    // 删空了就没必要继续停在编辑态
                    if (repository.favorites().isEmpty()) {
                        setEditMode(false);
                    }
                })
                .show();
    }

    /** 整理态开关：让两个适配器同步显隐删除入口 */
    private void setEditMode(boolean enabled) {
        editMode = enabled;
        gridAdapter.setDeleteMode(enabled);
        listAdapter.setDeleteMode(enabled);
        binding.btnDone.setSelected(enabled);
        binding.btnDone.setAlpha(enabled ? 1f : 0.55f);
        binding.shelfFooter.setText(enabled
                ? getString(R.string.shelf_manage_hint)
                : getString(R.string.shelf_footer, favorites().size()));
    }

    /** 行卡第二行：收藏时间 + 作者 */
    private String favoriteMeta(@NonNull Poem poem) {
        String author = poem.getAuthorLabel();
        long at = poem.getFavoriteAt();
        if (at <= 0) {
            return author;
        }
        String when = HistoryActivity.formatReadAt(at);
        return when.isEmpty() ? author
                : getString(R.string.shelf_fav_at, when) + " · " + author;
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
                    store.setShelfSort(index);
                    refresh();
                }
            });
            binding.sortChips.addView(chip);
        }
    }

    private void setupViewToggle() {
        updateToggleState();
        binding.btnDone.setSelected(editMode);
        binding.btnDone.setAlpha(editMode ? 1f : 0.55f);
        // 打开书架就直接是整理态，所以这里不再需要一个「编辑」入口；
        // 「完成」负责收起全部 ✕，再点一次（或长按任意卡片）可重新展开
        binding.btnDone.setOnClickListener(v -> {
            if (editMode) {
                setEditMode(false);
                Toast.makeText(requireContext(), R.string.shelf_done_hint,
                        Toast.LENGTH_SHORT).show();
            } else {
                setEditMode(true);
            }
        });
        binding.btnGrid.setOnClickListener(v -> {
            gridMode = true;
            store.setShelfGrid(true);
            updateToggleState();
            refresh();
        });
        binding.btnList.setOnClickListener(v -> {
            gridMode = false;
            store.setShelfGrid(false);
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

    @NonNull
    private List<Poem> favorites() {
        List<Poem> list = new ArrayList<>(repository.favorites());
        if (sortMode == 1) {
            Collections.sort(list, (a, b) -> a.getKind().getCode().compareTo(b.getKind().getCode()));
        } else if (sortMode == 2) {
            Collections.sort(list, (a, b) -> b.getScore() - a.getScore());
        }
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            return list;
        }
        String lower = keyword.toLowerCase(Locale.CHINA);
        List<Poem> matched = new ArrayList<>();
        for (Poem poem : list) {
            if (poem.getTitle().toLowerCase(Locale.CHINA).contains(lower)
                    || poem.getAuthorName().toLowerCase(Locale.CHINA).contains(lower)
                    || poem.getBody().contains(keyword)) {
                matched.add(poem);
            }
        }
        return matched;
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

        if (empty) {
            if (query.isEmpty()) {
                binding.emptyState.emptyTitle.setText(R.string.shelf_empty_title);
                binding.emptyState.emptyDesc.setText(R.string.shelf_empty_desc);
            } else {
                binding.emptyState.emptyTitle.setText(
                        getString(R.string.shelf_search_empty, query));
                binding.emptyState.emptyDesc.setText("");
            }
        }
        boolean hasAny = !repository.favorites().isEmpty();
        binding.shelfSearch.setVisibility(hasAny ? View.VISIBLE : View.GONE);
        binding.btnClear.setVisibility(hasAny ? View.VISIBLE : View.GONE);
        binding.btnDone.setVisibility(hasAny ? View.VISIBLE : View.GONE);
        // 书架空了就退出编辑态，免得留一个点不动的「完成」按钮
        if (!hasAny && editMode) {
            setEditMode(false);
        }
        binding.emptyState.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.shelfList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.resumeCard.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.shelfFooter.setText(editMode
                ? getString(R.string.shelf_edit_hint)
                : getString(R.string.shelf_footer, list.size()));

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

        gridAdapter.setDeleteMode(editMode);
        listAdapter.setDeleteMode(editMode);

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
}
