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
    /** 作者的内容派生稳定键。有没有它决定了后面按 uid 还是按数字 id 去查 */
    private static final String EXTRA_UID = "extra_author_uid";
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
    /** 篇数兜底已经查到真实值：不必再随就绪广播重查 */
    private boolean countLoaded;

    public static void open(@NonNull Context context, @NonNull Author author) {
        Intent intent = new Intent(context, AuthorDetailActivity.class);
        intent.putExtra(EXTRA_ID, author.getId());
        intent.putExtra(EXTRA_UID, author.getUid());
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
        refreshPoemCount();

        readyWatcher = new DbStatusListener.ReadyWatcher() {
            @Override
            protected void onReadyChanged(boolean ready, @NonNull DbStatus status) {
                // 首次进来时库可能还没开完，篇数兜底查询会落空，就绪后再补一次
                refreshPoemCount();
                loadWorks();
            }
        };
        repository.addDbStatusListener(readyWatcher);
    }

    /**
     * 篇数兜底：入口没带篇数（{@code EXTRA_N_POEMS} = 0，比如从列表页按作者筛进来）时，
     * 补查一次本库的实际首数。
     *
     * <p>不能用 {@code authors.n_poems} 兜底——那是**母库**篇数（陆游标着 9416，本库只有 7 首），
     * 界面上的「存世多少首」必须是现算的 {@code COUNT(*)}。后端同一列同样不可信，
     * 它给回来的 {@code nPoems} 也是现算的，所以远端与本地两条路都对得上。
     *
     * <p>查的是 {@link Author#getLookupRef()}（uid 优先）而不是数字 id：
     * 后端按 uid 认作者，本地库则要先在 {@code authors} 里换算出 {@code id}。
     *
     * <p>查不到（库还没就绪 / 作者确实没有作品）就把标志位留着，
     * 等 {@code ReadyWatcher} 下一次广播再试；拿到正值才收工，避免反复重刷。
     */
    private void refreshPoemCount() {
        if (countLoaded || author.getNPoems() > 0) {
            return;
        }
        repository.countByAuthor(author.getLookupRef(), new Callback<Integer>() {
            @Override
            public void onData(@NonNull Integer n) {
                if (isFinishing() || isDestroyed() || n <= 0) {
                    return;
                }
                countLoaded = true;
                author.setNPoems(n);
                bindHeader();
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // 查不到就维持「0 首」的显示，不打扰用户
            }
        });
    }

    @NonNull
    private Author readAuthor(@NonNull Intent intent) {
        Author a = new Author();
        a.setId(intent.getLongExtra(EXTRA_ID, 0L));
        a.setUid(intent.getStringExtra(EXTRA_UID));
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
                author.getLookupRef()));
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
        repository.listByAuthor(author.getLookupRef(), WORKS_LIMIT, new Callback<List<Poem>>() {
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
