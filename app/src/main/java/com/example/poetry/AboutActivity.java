package com.example.poetry;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.poetry.data.Callback;
import com.example.poetry.data.DbStatus;
import com.example.poetry.data.DbStatusListener;
import com.example.poetry.data.PoetryRepository;
import com.example.poetry.data.local.UserStore;
import com.example.poetry.databinding.ActivityAboutBinding;
import com.example.poetry.media.Speaker;
import com.example.poetry.ui.Skin;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 关于页：版本、诗库来源、离线语音方案与隐私说明。
 *
 * 显示的数据都来自本机（包信息、仓库状态、语音包是否就位）；唯一的出口是
 * 「检查更新」按钮，它只拉一个几百字节的 {@code version.json}。
 */
public class AboutActivity extends AppCompatActivity {

    private ActivityAboutBinding binding;
    private PoetryRepository repository;
    private UserStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /**
     * 诗库装好后要重画「诗库来源」那一行。注册在 {@code onCreate}、摘在 {@code onDestroy}。
     *
     * <p>比 {@link #recreate()} 精准：装库只影响这一行，重建整个页面是白费，
     * 而且会把刚弹出来的那个 Toast 一起带走。
     */
    private final DbStatusListener statusWatcher = status -> {
        // 只在**装完**这一下动手。每个事件都重新问一次 localState，等于在下载过程里
        // 每秒好几次往 IO 线程塞任务，而那时唯一在变的是进度百分比，这一页根本不显示它。
        if (status.state == DbStatus.State.INSTALLED) {
            onLibraryInstalled();
        }
    };

    /** 导出：让用户挑一个位置保存 JSON 备份 */
    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                    this::writeBackup);

    /** 恢复：让用户挑一份之前的 JSON 备份 */
    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    this::readBackup);

    public static void open(@Nullable Context context) {
        if (context != null) {
            context.startActivity(new Intent(context, AboutActivity.class));
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);
        binding = ActivityAboutBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = PoetryRepository.get(this);
        store = UserStore.get(this);
        binding.backButton.setOnClickListener(v -> finish());

        bindVersion();
        bindLibrary();
        bindVoice();
        bindBackup();

        binding.aboutCheckUpdate.setOnClickListener(v -> checkDbUpdate());
        repository.addDbStatusListener(statusWatcher);
    }

    /**
     * 强制检查一次（绕开 6 小时节流）。这里**不**读 {@link DbStatus}——事件是广播，
     * 而「用户亲手点了一下」需要的是这一问一答的结果，所以走回调。
     */
    private void checkDbUpdate() {
        binding.aboutCheckUpdate.setEnabled(false);
        // 断网时这一下最坏要等 15 秒连接超时，不给反馈会让人以为按钮坏了
        Toast.makeText(this, R.string.about_check_update_busy, Toast.LENGTH_SHORT).show();
        repository.checkForDbUpdate(true, new Callback<Boolean>() {
            @Override
            public void onData(@NonNull Boolean upToDate) {
                if (!canTouchViews()) {
                    return;
                }
                binding.aboutCheckUpdate.setEnabled(true);
                Toast.makeText(AboutActivity.this, upToDate
                        ? R.string.about_check_update_done
                        : R.string.about_check_update_found, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(@Nullable Throwable error) {
                if (!canTouchViews()) {
                    return;
                }
                binding.aboutCheckUpdate.setEnabled(true);
                String reason = error == null ? null : error.getMessage();
                Toast.makeText(AboutActivity.this, getString(R.string.about_check_update_failed,
                        reason == null ? getString(R.string.mine_data_failed) : reason),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 诗库刚装好：重画来源那一行，并告诉用户装成了。 */
    private void onLibraryInstalled() {
        if (!canTouchViews()) {
            return;
        }
        bindLibrary();
        Toast.makeText(this, R.string.mine_data_ready, Toast.LENGTH_SHORT).show();
    }

    /** 回调可能比 Activity 活得久（检查最长可达 15 秒），碰 view 之前统一过这一道。 */
    private boolean canTouchViews() {
        return binding != null && !isFinishing() && !isDestroyed();
    }

    private void bindVersion() {
        String version = "1.0";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null) {
                version = info.versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // 取不到就用默认值
        }
        binding.aboutVersion.setText(getString(R.string.about_version, version));
        String brand = getString(R.string.app_name);
        binding.aboutIcon.setText(brand.substring(0, 1));
        ViewCompat.setBackgroundTintList(binding.aboutIcon, ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.tenghuang_100)));
    }

    private void bindLibrary() {
        refreshLibrary();
        // 本地库是否就位要连一次数据库，放到 IO 线程再回主线程。
        // 连上之后 {@code sourceLabel()} 给出的说法会更准（从「内置示例数据」
        // 变成「本地诗库 poetry.db」），所以这里只负责再刷一遍，不自己拼文案。
        repository.localState(new Callback<Boolean>() {
            @Override
            public void onData(@NonNull Boolean localReady) {
                if (binding == null) {
                    // Activity 已经销毁：binding 被置空，刷下去就是 NPE
                    return;
                }
                refreshLibrary();
            }

            @Override
            public void onError(@Nullable Throwable error) {
                // 保持仓库给出的默认文案
            }
        });
    }

    /**
     * 「数据来源」+「服务端」两行。
     *
     * <p>分开写是因为它们回答的是两个问题：内容这次是从哪儿来的（可能回退了本地），
     * 以及后端链路本身通不通（熔断、未配置地址、无网络都会体现在这里）。
     * 合成一行会让「正在用本地库」和「后端挂了」看起来像同一件事。
     */
    private void refreshLibrary() {
        binding.aboutSource.setText(getString(R.string.data_source_label,
                repository.sourceLabel()));
        binding.aboutApi.setText(getString(R.string.about_api_label,
                repository.apiStatusLabel()));
    }

    /**
     * 「语音引擎」一行如实显示引擎当前的状态（几个音色可用、还是模型没加载起来）。
     * <p>
     * 模型是异步加载的，所以先按当前状态显示，等 prepare 的回调到了再刷一次。
     */
    private void bindVoice() {
        refreshVoiceState();
        Speaker.get().prepare(this, this::refreshVoiceState);
    }

    private void refreshVoiceState() {
        if (binding == null || isFinishing()) {
            return;
        }
        binding.aboutVoiceState.setText(getString(R.string.about_voice_state,
                Speaker.get().statusText(this)));
    }

    private void bindBackup() {
        binding.aboutDataPath.setText(getString(R.string.about_data_path,
                store.dataFile().getAbsolutePath()));
        binding.aboutExportData.setOnClickListener(v -> {
            String name = "poetry-backup-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA)
                    .format(new Date()) + ".json";
            exportLauncher.launch(name);
        });
        binding.aboutImportData.setOnClickListener(v ->
                importLauncher.launch(new String[]{"application/json", "text/plain", "*/*"}));
    }

    /** 把整份数据写成 JSON 备份；文件读写放 IO 线程，结果回主线程提示 */
    private void writeBackup(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        io.execute(() -> {
            String message = null;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    message = getString(R.string.about_export_failed, "无法写入目标位置");
                } else {
                    out.write(store.exportJson().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception e) {
                message = getString(R.string.about_export_failed,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String result = message;
            runOnUiThread(() -> {
                if (result == null) {
                    Toast.makeText(this, R.string.about_export_done, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** 读取一份 JSON 备份并整体恢复；格式不对时提示，且不动现有数据 */
    private void readBackup(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        io.execute(() -> {
            boolean ok = false;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    StringBuilder text = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            text.append(line).append('\n');
                        }
                    }
                    ok = store.importJson(text.toString());
                }
            } catch (Exception ignored) {
                ok = false;
            }
            final boolean success = ok;
            runOnUiThread(() -> {
                Toast.makeText(this, success ? R.string.about_import_done
                        : R.string.about_import_failed, Toast.LENGTH_SHORT).show();
                if (success) {
                    recreate();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 先摘订阅再放开 binding：晚一步的话，队列里的那次事件会撞上已销毁的视图
        repository.removeDbStatusListener(statusWatcher);
        binding = null;
        io.shutdownNow();
    }
}
