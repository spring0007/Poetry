package com.example.poetry.adapter;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.example.poetry.data.model.Poem;

import java.util.List;
import java.util.Objects;

/**
 * 诗列表的刷新动作：算 diff、换数据、通知 adapter。
 *
 * <p>四个装着 {@link Poem} 的适配器（卡片、行卡、热度榜、搜索面板）共用这一份规则，
 * 不再各自 {@code notifyDataSetChanged()}。后者每次都把可见条目全部重绑 ——
 * 列表页的取数是「本地先出图 → 远端再刷一次」，天然会连着 submit 两次，
 * 没有 diff 的话第二次会肉眼可见地闪一下，而且卡在滚动时更明显。
 *
 * <h3>哪些字段算「内容变了」</h3>
 * 只比**这四个适配器真正画在屏幕上的东西**：标题、作者、朝代、体裁（由 srcName 反解）、
 * 正文（摘录、行数都从它派生）、热度分（星级与百分比）、收藏时间（书架行卡的元信息）。
 * 不比 {@code notes} / {@code translation} 这些卡片上根本不显示、数据量又大的字段 ——
 * 那等于每次刷新都白比几 MB 字符串。
 *
 * <p>{@code body} 在比较之列，而远端列表接口有意不带正文；「本地 → 远端」这一步不会
 * 因此误判，因为仓库会用本地已有的正文把远端那一页补上
 * （见 {@code PoetryRepository#carryOverBodies}），同一首诗两次拿到的 {@code body} 一致。
 *
 * <p>位置**也算内容**：热度榜的排名徽标是从 position 算出来的，而 DiffUtil 对
 * 「移动」只发 {@code onMoved}、不会重新绑定那一条，光比字段会让排名停在旧值上。
 * 代价只有真正挪了位的条目要重绑一次，可接受。
 */
public final class PoemDiff {

    private PoemDiff() {
    }

    /**
     * 把 {@code target} 换成 {@code fresh} 的内容，并按 diff 通知 {@code adapter}。
     *
     * <p>{@code target} 必须是 adapter 正在用的那份列表（就是 {@code onBindViewHolder}
     * 里读的那一个），不能是副本 —— 换的是它的内容，不是引用。
     */
    public static void submit(@NonNull RecyclerView.Adapter<?> adapter,
                              @NonNull List<Poem> target,
                              @NonNull List<Poem> fresh) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new Callback(target, fresh), true);
        target.clear();
        target.addAll(fresh);
        diff.dispatchUpdatesTo(adapter);
    }

    private static final class Callback extends DiffUtil.Callback {

        private final List<Poem> oldList;
        private final List<Poem> newList;

        Callback(@NonNull List<Poem> oldList, @NonNull List<Poem> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        /**
         * 「是不是同一首」用 {@link Poem#identityKey()}（uid 优先）——这也正是
         * {@link Poem#equals(Object)} 的语义。{@code id} 是母库 ROWID，换一次库就整体错位，
         * 拿它当身份会让整份列表错位。
         */
        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            return oldList.get(oldPos).identityKey().equals(newList.get(newPos).identityKey());
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            if (oldPos != newPos) {
                // 位置挪了：排名、序号这类从 position 派生的显示要重画
                return false;
            }
            Poem a = oldList.get(oldPos);
            Poem b = newList.get(newPos);
            return a.getScore() == b.getScore()
                    && a.getFavoriteAt() == b.getFavoriteAt()
                    && Objects.equals(a.getTitle(), b.getTitle())
                    && Objects.equals(a.getAuthorName(), b.getAuthorName())
                    && Objects.equals(a.getDynasty(), b.getDynasty())
                    && Objects.equals(a.getSrcName(), b.getSrcName())
                    && Objects.equals(a.getBody(), b.getBody());
        }
    }
}
