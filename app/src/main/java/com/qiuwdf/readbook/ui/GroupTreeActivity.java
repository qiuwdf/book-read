package com.qiuwdf.readbook.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.qiuwdf.readbook.R;
import com.qiuwdf.readbook.core.Book;
import com.qiuwdf.readbook.core.Bookshelf;
import com.qiuwdf.readbook.core.GroupTree;
import com.qiuwdf.readbook.widget.GuideLineView;

import java.util.ArrayList;
import java.util.List;

/**
 * 全屏的树状分组选择页：最上面是「全部」，下面按存储目录的文件夹层级展示，
 * 仿资源管理器的 ＋/－ 展开收起；点某一层即选中该列表并返回书架。
 * 内容超出一屏可上下滚动，文件夹名过长可左右滚动；右上角 X 关闭（不改变选择）。
 *
 * <p>必须继承 {@code AppCompatActivity}：AppCompat 的夜间模式（{@code MODE_NIGHT_*}）
 * 是靠 AppCompatDelegate 逐个 Activity 套用的，普通 {@code android.app.Activity}
 * 拿不到代理，本页的 {@code @color/bar_bg}、{@code @color/page_bg} 会按**系统**的
 * 白天/黑夜取值 —— 系统白天 + App 设夜间时整页仍是白的。
 */
public class GroupTreeActivity extends AppCompatActivity {

    /** 结果 extra：选中的分组路径（"" = 全部） */
    public static final String RESULT_EXTRA_GROUP = "group";

    private static final int ROW_HEIGHT_DP = 44;
    private static final int INDENT_DP = 20;      // 每深一层缩进
    private static final int BASE_PAD_DP = 10;    // 行左端基准留白

    private GroupTree.Node mRoot;
    private final List<GroupTree.Node> mVisible = new ArrayList<GroupTree.Node>();
    private String mCurrent;

    private LinearLayout mContainer;
    private ScrollView mScroll;
    private int mRowWidthPx;

    /** 进入本页：current 为书架当前选中的分组路径（"" = 全部） */
    public static Intent createIntent(Context c, String current) {
        Intent it = new Intent(c, GroupTreeActivity.class);
        it.putExtra(RESULT_EXTRA_GROUP, current == null ? "" : current);
        return it;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_tree);

        mCurrent = getIntent().getStringExtra(RESULT_EXTRA_GROUP);
        if (mCurrent == null) {
            mCurrent = "";
        }

        findViewById(R.id.btn_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();   // 不带结果 = 保持原选择
            }
        });

        mScroll = (ScrollView) findViewById(R.id.tree_scroll);
        mContainer = (LinearLayout) findViewById(R.id.tree_container);

        List<Book> books = Bookshelf.get(this).getBooks();
        mRoot = GroupTree.build(books);
        if (mCurrent.length() > 0) {
            GroupTree.reveal(mRoot, mCurrent);   // 展开到当前选中项，让高亮看得见
        }
        rebuild();
    }

    /** 按当前展开状态重画整棵树 */
    private void rebuild() {
        mVisible.clear();
        GroupTree.collectVisible(mRoot, mVisible);

        int screen = getResources().getDisplayMetrics().widthPixels;
        mRowWidthPx = measureRowWidth(screen);
        mContainer.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (GroupTree.Node n : mVisible) {
            mContainer.addView(makeRow(inf, n));
        }

        // 把当前选中项滚到可见位置
        if (mCurrent.length() > 0) {
            final int index = indexOfPath(mCurrent);
            if (index > 0) {
                int y = index * dp(ROW_HEIGHT_DP);
                mScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        mScroll.scrollTo(0, Math.max(0, y - dp(ROW_HEIGHT_DP)));
                    }
                });
            }
        }
    }

    private LinearLayout makeRow(LayoutInflater inf, final GroupTree.Node n) {
        LinearLayout row = (LinearLayout) inf.inflate(R.layout.item_tree_group, mContainer, false);
        row.setPadding(dp(BASE_PAD_DP), 0, dp(12), 0);
        row.getLayoutParams().width = mRowWidthPx;
        row.setMinimumHeight(dp(ROW_HEIGHT_DP));
        row.setSelected(n.path.equals(mCurrent));

        // 引导线：每格一层缩进（宽度 = INDENT_DP，与原来按深度加的 padding 相同）
        LinearLayout guides = (LinearLayout) row.findViewById(R.id.guide_container);
        for (int k = 0; k < n.depth; k++) {
            GuideLineView g = new GuideLineView(this);
            g.setLayoutParams(new LinearLayout.LayoutParams(dp(INDENT_DP),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            if (k == n.depth - 1) {
                // 最后一格连接到父节点：├（后面还有兄弟）或 └（最后一个孩子）
                g.setMode(hasLaterSibling(n)
                        ? GuideLineView.TEE : GuideLineView.CORNER);
            } else {
                // 中间的格子对应各层祖先：祖先后面还有兄弟才画竖线
                GroupTree.Node a = n;
                while (a.depth > k + 1) {
                    a = a.parent;
                }
                g.setMode(hasLaterSibling(a)
                        ? GuideLineView.LINE : GuideLineView.BLANK);
            }
            guides.addView(g);
        }

        ImageView toggle = (ImageView) row.findViewById(R.id.node_toggle);
        if (n.hasChildren()) {
            toggle.setVisibility(View.VISIBLE);
            // API 14 上矢量图必须经 AppCompatResources 解析
            toggle.setImageDrawable(androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    this, n.expanded ? R.drawable.ic_node_collapse : R.drawable.ic_node_expand));
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    n.expanded = !n.expanded;
                    rebuild();
                }
            });
        } else {
            // 没有子文件夹：占位对齐，但不可点
            toggle.setVisibility(View.INVISIBLE);
            toggle.setOnClickListener(null);
        }

        ((TextView) row.findViewById(R.id.node_name)).setText(n.name.length() == 0
                ? getString(R.string.group_all) : n.name);
        ((TextView) row.findViewById(R.id.node_count)).setText(String.valueOf(n.bookCount));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent data = new Intent();
                data.putExtra(RESULT_EXTRA_GROUP, n.path);
                setResult(RESULT_OK, data);
                finish();
            }
        });
        return row;
    }

    /**
     * 整行宽度：取「屏幕宽」与「内容最宽的一行」的较大者 ——
     * 这样短行也能整行高亮、长名则把容器撑宽交给横向滚动。
     */
    private int measureRowWidth(int screenWidth) {
        Paint name = new Paint();
        name.setTextSize(sp(15));
        Paint count = new Paint();
        count.setTextSize(sp(12));

        float countW = count.measureText(String.valueOf(mRoot.bookCount)) + dp(8);
        float max = 0;
        for (GroupTree.Node n : mVisible) {
            float w = dp(BASE_PAD_DP + n.depth * INDENT_DP)   // 缩进
                    + dp(30)                                   // ＋/－ 位
                    + dp(26);                                  // 文件夹图标位
            w += n.name.length() == 0 ? 0 : name.measureText(n.name);
            w += countW + dp(12);
            max = Math.max(max, w);
        }
        return (int) Math.max(screenWidth, max);
    }

    /** 该节点在同层里是否还有排在其后的兄弟（决定画 └ 还是 ├、竖线是否贯穿） */
    private static boolean hasLaterSibling(GroupTree.Node node) {
        GroupTree.Node p = node.parent;
        return p != null && p.children.get(p.children.size() - 1) != node;
    }

    private int indexOfPath(String path) {
        for (int i = 0; i < mVisible.size(); i++) {
            if (mVisible.get(i).path.equals(path)) {
                return i;
            }
        }
        return -1;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private float sp(int v) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    // 供无设备测试断言可见行
    ViewGroup rowContainerForTest() {
        return mContainer;
    }
}
