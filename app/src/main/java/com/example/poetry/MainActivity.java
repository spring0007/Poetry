package com.example.poetry;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.example.poetry.databinding.ActivityMainBinding;
import com.example.poetry.fragment.BookshelfFragment;
import com.example.poetry.fragment.CategoryFragment;
import com.example.poetry.fragment.DiscoverFragment;
import com.example.poetry.fragment.MineFragment;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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
}
