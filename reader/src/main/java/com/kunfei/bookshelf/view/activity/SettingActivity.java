package com.kunfei.bookshelf.view.activity;

import android.content.Context;
import android.content.Intent;
import android.view.MenuItem;
import android.widget.LinearLayout;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.kunfei.basemvplib.impl.IPresenter;
import com.kunfei.bookshelf.R;
import com.kunfei.bookshelf.R2;
import com.kunfei.bookshelf.base.MBaseActivity;
import com.kunfei.bookshelf.utils.theme.ThemeStore;
import com.kunfei.bookshelf.view.fragment.SettingsFragment;

import butterknife.BindView;
import butterknife.ButterKnife;

/**
 * Created by GKF on 2017/12/16.
 * 设置
 */

public class SettingActivity extends MBaseActivity {
    @BindView(R2.id.toolbar)
    Toolbar toolbar;
    @BindView(R2.id.ll_content)
    LinearLayout llContent;

    private SettingsFragment settingsFragment = new SettingsFragment();

    public static void startThis(Context context) {
        context.startActivity(new Intent(context, SettingActivity.class));
    }

    @Override
    protected IPresenter initInjector() {
        return null;
    }

    @Override
    protected void onCreateActivity() {
        getWindow().getDecorView().setBackgroundColor(ThemeStore.backgroundColor(this));
        setContentView(R.layout.activity_settings);
        ButterKnife.bind(this);
        this.setSupportActionBar(toolbar);
        setupActionBar(getString(R.string.setting));

        getFragmentManager().beginTransaction()
                .replace(R.id.settingsFrameLayout, settingsFragment, "settings")
                .commit();

    }

    @Override
    protected void initData() {

    }

    //设置ToolBar
    public void setupActionBar(String title) {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(title);
        }
    }

    //菜单
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void finish() {
        if (getFragmentManager().findFragmentByTag("settings") == null) {
            getFragmentManager().beginTransaction()
                    .replace(R.id.settingsFrameLayout, settingsFragment, "settings")
                    .commit();
        } else {
            super.finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    public void initImmersionBar() {
        super.initImmersionBar();
    }

}
