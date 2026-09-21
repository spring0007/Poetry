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

import com.example.poetry.PoemListActivity;
import com.example.poetry.R;
import com.example.poetry.adapter.AuthorAdapter;
import com.example.poetry.adapter.CategoryAdapter;
import com.example.poetry.adapter.DynastyAdapter;
import com.example.poetry.data.Callback;
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

        setupTypes();
        setupDynasties();
        setupThemes();
        setupAuthors();
    }

    // ---------------------------------------------------------------- 按体裁

    private void setupTypes() {
        binding.typeHeader.sectionTitle.setText(R.string.section_by_type);
        typeAdapter = new CategoryAdapter(category ->
                PoemListActivity.open(requireContext(), category.getName(),
                        PoemListActivity.MODE_KIND, category.getCode()));
        binding.typeList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.typeList.setAdapter(typeAdapter);
        binding.typeList.setNestedScrollingEnabled(false);

        repository.kindCategories(new Callback<List<Category>>() {
            @Override
            public void onData(@NonNull List<Category> categories) {
                if (binding == null) {
                    return;
                }
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
            }
        });
    }

    // ---------------------------------------------------------------- 按朝代

    private void setupDynasties() {
        binding.dynastyHeader.sectionTitle.setText(R.string.section_by_dynasty);
        dynastyAdapter = new DynastyAdapter(category ->
                PoemListActivity.open(requireContext(), category.getName() + "代作品",
                        PoemListActivity.MODE_DYNASTY, category.getCode()));
        binding.dynastyList.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.dynastyList.setAdapter(dynastyAdapter);
        binding.dynastyList.setNestedScrollingEnabled(false);

        repository.dynastyCategories(new Callback<List<Category>>() {
            @Override
            public void onData(@NonNull List<Category> categories) {
                if (binding == null) {
                    return;
                }
                dynastyAdapter.submit(categories);
                binding.dynastyHeader.sectionMeta.setText(categories.size() + " 个");
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // ignore
            }
        });
    }

    // ---------------------------------------------------------------- 按主题

    private void setupThemes() {
        binding.themeHeader.sectionTitle.setText(R.string.section_by_theme);
        binding.themeChips.removeAllViews();
        repository.themeCategories(new Callback<List<Category>>() {
            @Override
            public void onData(@NonNull List<Category> categories) {
                if (binding == null) {
                    return;
                }
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
            }
        });
    }

    // ---------------------------------------------------------------- 热门作者

    private void setupAuthors() {
        binding.authorHeader.sectionTitle.setText(R.string.section_by_author);
        binding.authorHeader.sectionMeta.setText(R.string.section_by_author_meta);
        authorAdapter = new AuthorAdapter(author ->
                PoemListActivity.open(requireContext(), author.getName() + " 作品集",
                        PoemListActivity.MODE_AUTHOR, String.valueOf(author.getId())));
        binding.authorList.setLayoutManager(new LinearLayoutManager(requireContext(),
                LinearLayoutManager.HORIZONTAL, false));
        binding.authorList.setAdapter(authorAdapter);
        binding.authorList.setNestedScrollingEnabled(false);

        repository.topAuthors(12, new Callback<List<Author>>() {
            @Override
            public void onData(@NonNull List<Author> authors) {
                if (binding == null) {
                    return;
                }
                authorAdapter.submit(authors);
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // ignore
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
