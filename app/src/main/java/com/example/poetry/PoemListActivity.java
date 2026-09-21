package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.poetry.adapter.PoemCardAdapter;
import com.example.poetry.data.Callback;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ActivityPoemListBinding;

import java.util.List;

/**
 * 分类结果列表页：按体裁 / 朝代 / 主题 / 作者筛选后的作品列表。
 * <p>
 * 由分类页、朝代宫格、主题 chip、作者头像四种入口复用。
 */
public class PoemListActivity extends AppCompatActivity {

    private static final String EXTRA_TITLE = "extra_title";
    private static final String EXTRA_MODE = "extra_mode";
    private static final String EXTRA_VALUE = "extra_value";

    public static final String MODE_KIND = "kind";
    public static final String MODE_DYNASTY = "dynasty";
    public static final String MODE_THEME = "theme";
    public static final String MODE_AUTHOR = "author";
    public static final String MODE_SEARCH = "search";

    private ActivityPoemListBinding binding;
    private PoetryRepository repository;
    private PoemCardAdapter adapter;

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
        super.onCreate(savedInstanceState);
        binding = ActivityPoemListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = PoetryRepository.get(this);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        String value = getIntent().getStringExtra(EXTRA_VALUE);
        binding.listTitle.setText(title == null ? getString(R.string.app_name) : title);
        binding.backButton.setOnClickListener(v -> finish());

        adapter = new PoemCardAdapter(new PoemCardAdapter.Listener() {
            @Override
            public void onPoemClick(@NonNull Poem poem) {
                DetailActivity.open(PoemListActivity.this, poem);
            }

            @Override
            public void onFavoriteClick(@NonNull Poem poem) {
                boolean added = repository.toggleFavorite(poem);
                android.widget.Toast.makeText(PoemListActivity.this,
                        added ? R.string.shelf_added : R.string.shelf_removed,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        binding.poemList.setLayoutManager(new LinearLayoutManager(this));
        binding.poemList.setAdapter(adapter);

        load(mode == null ? MODE_KIND : mode, value == null ? "" : value);
    }

    private void load(@NonNull String mode, @NonNull String value) {
        binding.loadingBar.setVisibility(View.VISIBLE);
        Callback<List<Poem>> callback = new Callback<List<Poem>>() {
            @Override
            public void onData(@NonNull List<Poem> poems) {
                binding.loadingBar.setVisibility(View.GONE);
                adapter.submit(poems);
                boolean empty = poems.isEmpty();
                binding.emptyState.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
                binding.poemList.setVisibility(empty ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onError(@Nullable Throwable error) {
                binding.loadingBar.setVisibility(View.GONE);
                binding.emptyState.getRoot().setVisibility(View.VISIBLE);
                binding.poemList.setVisibility(View.GONE);
            }
        };

        switch (mode) {
            case MODE_DYNASTY:
                repository.listByDynasty(value, 60, callback);
                break;
            case MODE_THEME:
                repository.listByTheme(value, 60, callback);
                break;
            case MODE_AUTHOR:
                repository.listByAuthor(Long.parseLong(value), 60, callback);
                break;
            case MODE_SEARCH:
                repository.search(value, 60, callback);
                break;
            case MODE_KIND:
            default:
                repository.listByKind(value, 60, callback);
                break;
        }
    }
}
