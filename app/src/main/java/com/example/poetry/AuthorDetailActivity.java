package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.poetry.adapter.PoemCardAdapter;
import com.example.poetry.data.Callback;
import com.example.poetry.data.DbStatus;
import com.example.poetry.data.DbStatusListener;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.model.Author;
import com.example.poetry.data.model.Dynasty;
import com.example.poetry.data.model.Poem;
import com.example.poetry.databinding.ActivityAuthorDetailBinding;
import com.example.poetry.ui.Skin;

import java.util.List;

/**
 * 作者详情页：头像 / 姓名 / 朝代 / 存世作品数 + 作者简介 + 代表作品列表。
 * <p>
 * 入口是分类页「热门作者」头像。标题栏与返回行为与作品详情页一致：
 * MaterialToolbar + 返回键 finish()。
 */
public class AuthorDetailActivity extends AppCompatActivity {

    private static final String EXTRA_ID = "extra_author_id";
    private static final String EXTRA_NAME = "extra_author_name";
    private static final String EXTRA_DYNASTY = "extra_author_dynasty";
    private static final String EXTRA_DESC = "extra_author_desc";
    private static final String EXTRA_N_POEMS = "extra_author_n_poems";

    /** 代表作品条数 */
    private static final int WORKS_LIMIT = 12;

    private ActivityAuthorDetailBinding binding;
    private PoetryRepository repository;
    private PoemCardAdapter adapter;

    private Author author;
    private DbStatusListener readyWatcher;

    public static void open(@NonNull Context context, @NonNull Author author) {
        Intent intent = new Intent(context, AuthorDetailActivity.class);
        intent.putExtra(EXTRA_ID, author.getId());
        intent.putExtra(EXTRA_NAME, author.getName());
        intent.putExtra(EXTRA_DYNASTY, author.getDynasty());
        intent.putExtra(EXTRA_DESC, author.getDesc());
        intent.putExtra(EXTRA_N_POEMS, author.getNPoems());
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityAuthorDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = PoetryRepository.get(this);
        author = readAuthor(getIntent());

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.toolbar.setTitle(author.getName());

        bindHeader();
        bindWorks();

        readyWatcher = new DbStatusListener.ReadyWatcher() {
            @Override
            protected void onReadyChanged(boolean ready, @NonNull DbStatus status) {
                loadWorks();
            }
        };
        repository.addDbStatusListener(readyWatcher);
    }

    @NonNull
    private Author readAuthor(@NonNull Intent intent) {
        Author a = new Author();
        a.setId(intent.getLongExtra(EXTRA_ID, 0L));
        a.setName(intent.getStringExtra(EXTRA_NAME));
        a.setDynasty(intent.getStringExtra(EXTRA_DYNASTY));
        a.setDesc(intent.getStringExtra(EXTRA_DESC));
        a.setNPoems(intent.getIntExtra(EXTRA_N_POEMS, 0));
        return a;
    }

    private void bindHeader() {
        binding.authorAvatar.setText(author.getInitial());
        binding.authorName.setText(author.getName());
        binding.authorMeta.setText(getString(R.string.author_meta,
                Dynasty.labelOf(author.getDynasty()), author.getNPoems()));
        binding.statDynastyValue.setText(Dynasty.labelOf(author.getDynasty()));
        binding.statPoemsValue.setText(String.valueOf(author.getNPoems()));

        String intro = author.getDesc() == null ? "" : author.getDesc().trim();
        if (intro.isEmpty()) {
            binding.authorIntro.setText(R.string.author_intro_empty);
        } else {
            binding.authorIntro.setText(intro);
        }

        binding.worksHeader.sectionTitle.setText(R.string.author_works);
        binding.worksHeader.sectionMeta.setText(
                getString(R.string.author_view_all, author.getNPoems()));
        binding.worksHeader.sectionMeta.setOnClickListener(v -> PoemListActivity.open(this,
                author.getName() + " 作品集", PoemListActivity.MODE_AUTHOR,
                String.valueOf(author.getId())));
    }

    private void bindWorks() {
        adapter = new PoemCardAdapter(new PoemCardAdapter.Listener() {
            @Override
            public void onPoemClick(@NonNull Poem poem) {
                DetailActivity.open(AuthorDetailActivity.this, poem);
            }

            @Override
            public void onFavoriteClick(@NonNull Poem poem) {
                boolean added = repository.toggleFavorite(poem);
                Toast.makeText(AuthorDetailActivity.this,
                        added ? R.string.shelf_added : R.string.shelf_removed,
                        Toast.LENGTH_SHORT).show();
            }
        });
        binding.worksList.setLayoutManager(new LinearLayoutManager(this));
        binding.worksList.setAdapter(adapter);
        binding.worksList.setNestedScrollingEnabled(false);
    }

    private void loadWorks() {
        binding.loadingBar.setVisibility(View.VISIBLE);
        repository.listByAuthor(author.getId(), WORKS_LIMIT, new Callback<List<Poem>>() {
            @Override
            public void onData(@NonNull List<Poem> poems) {
                binding.loadingBar.setVisibility(View.GONE);
                adapter.submit(poems);
                boolean empty = poems.isEmpty();
                binding.worksList.setVisibility(empty ? View.GONE : View.VISIBLE);
                binding.emptyState.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
                if (empty) {
                    binding.emptyState.emptyTitle.setText(R.string.empty_title);
                    binding.emptyState.emptyDesc.setText(R.string.empty_desc);
                }
            }

            @Override
            public void onError(@Nullable Throwable error) {
                binding.loadingBar.setVisibility(View.GONE);
                binding.worksList.setVisibility(View.GONE);
                binding.emptyState.getRoot().setVisibility(View.VISIBLE);
                binding.emptyState.emptyTitle.setText(R.string.empty_title);
                binding.emptyState.emptyDesc.setText(R.string.empty_desc);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (readyWatcher != null) {
            repository.removeDbStatusListener(readyWatcher);
            readyWatcher = null;
        }
        super.onDestroy();
    }
}
