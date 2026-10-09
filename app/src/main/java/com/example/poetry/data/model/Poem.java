package com.example.poetry.data.model;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 作品（诗词歌赋）。
 * <p>
 * 字段映射 {@code poetry.db → poems} 表：
 * id / uid / author_id / title / rhythmic_id / src_id / body / tags / score / n_char，
 * 富文本（{@code notes} / {@code translation} / {@code introduction} /
 * {@code creativeBackground} / {@code appreciation}）来自 {@code poem_extras} 表
 * （按 {@code uid} 关联），另外把联表得到的作者名、词牌名、数据源名、朝代一起带出来，
 * 避免 UI 层再查一次。
 * <p>
 * 说明：
 * <ul>
 *   <li>{@code body} 为简体、无空格文本，行与行之间用 '\n' 分隔；</li>
 *   <li>{@code tags} 使用 ASCII 单元分隔符 \u001F 分隔多项；{@code notes} 两种都认，见
 *       {@link #getNoteList()}；</li>
 *   <li>{@code score} 为 0–255 的热度分，用于「精选 / 热度」排序与星级展示；</li>
 *   <li>{@code strain} 为平仄串（来自可选的 poetry-strains.db）；</li>
 *   <li>{@code uid} 是内容派生的稳定键，跨库不变，外部富文本/图片数据要按它关联，
 *       不要按 id；{@code translation} 等无数据时为空串。</li>
 * </ul>
 */
public class Poem implements Parcelable {

    /** ASCII Unit Separator：tags / notes 的多值分隔符 */
    public static final String SEP = "\u001F";

    private long id;
    private long authorId;
    private String title;
    private String rhythmic;
    private String srcName;
    private String body;
    private String tags;
    private String notes;
    private int score;
    private int nChar;

    private String authorName;
    private String dynasty;
    private String strain;

    /** 内容派生的稳定键（poems.uid）：外部数据关联用，跨库不变 */
    private String uid;
    /** 译文：poem_extras.translation，旧库/后台不可用时为空 */
    private String translation;
    /** 赏析：poem_extras.appreciation */
    private String appreciation;
    /** 简介：poem_extras.introduction */
    private String introduction;
    /** 创作背景：poem_extras.creative_background */
    private String creativeBackground;

    /** 收藏时间（毫秒时间戳，0 表示未记录）；仅本地使用，不来自诗库 */
    private long favoriteAt;

    public Poem() {
        this.title = "";
        this.rhythmic = "";
        this.srcName = "";
        this.body = "";
        this.tags = "";
        this.notes = "";
        this.authorName = "";
        this.dynasty = "";
        this.strain = "";
        this.uid = "";
        this.translation = "";
        this.appreciation = "";
        this.introduction = "";
        this.creativeBackground = "";
    }

    // ---------------------------------------------------------------- 基础字段

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(long authorId) {
        this.authorId = authorId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    /** 词牌 / 曲牌（rhythmics.name），诗则为空 */
    public String getRhythmic() {
        return rhythmic;
    }

    public void setRhythmic(String rhythmic) {
        this.rhythmic = rhythmic == null ? "" : rhythmic;
    }

    /** 数据源名，如 tang-poem / song-ci */
    public String getSrcName() {
        return srcName;
    }

    public void setSrcName(String srcName) {
        this.srcName = srcName == null ? "" : srcName;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body == null ? "" : body;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags == null ? "" : tags;
    }

    /** 内容派生的稳定键（poems.uid）；内置示例数据没有，为空串 */
    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid == null ? "" : uid;
    }

    /**
     * 用户数据（收藏 / 历史 / 阅读进度 / 最近阅读时间）里的身份键。
     * <p>
     * 有 {@code uid} 就用 uid——它由内容派生，换库不变；内置示例数据没有 uid
     * （见 {@link #getUid()}），退化成 {@code "id:" + id}。加前缀是为了让退化键
     * 永远不会和 uid（UUID v5）撞车，也让「裸数字」这种旧格式一眼可辨。
     * <p>
     * {@code poems.id} 是母库 ROWID，换一次库就整体错位，所以除了这个退化分支，
     * 任何地方都不要拿 id 当身份用。
     */
    @NonNull
    public String identityKey() {
        String key = uid;
        return (key == null || key.isEmpty()) ? ("id:" + id) : key;
    }

    /**
     * 拿去向后端查这一首时用的引用。
     *
     * <p>与 {@link #identityKey()} 的区别：那个是**本地存储键**（没有 uid 时退化成
     * {@code "id:123"}，前缀就是为避免与 uid 撞车），这个是**接口参数**。
     * 后端 {@code /v1/poems/{ref}} 认「纯数字 = id，其余 = uid」，
     * 把 {@code id:123} 塞进去会被当成 uid，查不到。
     */
    @NonNull
    public String getLookupRef() {
        String key = uid;
        return (key == null || key.isEmpty()) ? String.valueOf(id) : key;
    }

    /** 注释：一整段文本，可能多行，也可能是 SEP 连接的多条，见 {@link #getNoteList()} */
    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes == null ? "" : notes;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public int getNChar() {
        return nChar;
    }

    public void setNChar(int nChar) {
        this.nChar = nChar;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName == null ? "" : authorName;
    }

    /** 朝代 code：tang / song / yuan … */
    public String getDynasty() {
        return dynasty;
    }

    public void setDynasty(String dynasty) {
        this.dynasty = dynasty == null ? "" : dynasty;
    }

    public String getStrain() {
        return strain;
    }

    public void setStrain(String strain) {
        this.strain = strain == null ? "" : strain;
    }

    public String getTranslation() {
        return translation;
    }

    public void setTranslation(String translation) {
        this.translation = translation == null ? "" : translation;
    }

    public String getAppreciation() {
        return appreciation;
    }

    public void setAppreciation(String appreciation) {
        this.appreciation = appreciation == null ? "" : appreciation;
    }

    /** 简介：整篇作品的来历/体例说明 */
    public String getIntroduction() {
        return introduction;
    }

    public void setIntroduction(String introduction) {
        this.introduction = introduction == null ? "" : introduction;
    }

    /** 创作背景：作者写作时的境况 */
    public String getCreativeBackground() {
        return creativeBackground;
    }

    public void setCreativeBackground(String creativeBackground) {
        this.creativeBackground = creativeBackground == null ? "" : creativeBackground;
    }

    /** 收藏时间；未收藏时为 0 */
    public long getFavoriteAt() {
        return favoriteAt;
    }

    public void setFavoriteAt(long favoriteAt) {
        this.favoriteAt = favoriteAt;
    }

    // ---------------------------------------------------------------- 派生字段

    /** 体裁（由 srcName 反解） */
    @NonNull
    public PoemKind getKind() {
        return PoemKind.fromSource(srcName);
    }

    /** 按 '\n' 切分正文，得到逐句列表 */
    @NonNull
    public List<String> getLines() {
        List<String> lines = new ArrayList<>();
        if (body.isEmpty()) {
            return lines;
        }
        String[] parts = body.split("\n");
        Collections.addAll(lines, parts);
        return lines;
    }

    public int getLineCount() {
        return getLines().size();
    }

    /** 卡片摘录：取前两行，过长时截断 */
    @NonNull
    public String getExcerpt() {
        List<String> lines = getLines();
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size() && i < 2; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /** 标签列表 */
    @NonNull
    public List<String> getTagList() {
        return splitMulti(tags);
    }

    /**
     * 注释列表（译文 / 赏析为空时，UI 用注释兜底）。
     *
     * <p>两种分隔符都认：内置示例数据（{@code SeedDataSource}）把多条注释用 {@link #SEP}
     * 连成一行，而诗库的 {@code poem_extras.notes} 是**一行一条**——同一个注释里也会有换行，
     * 所以这里 SEP 和 '\n' 一起切，切出来的空行丢掉。
     */
    @NonNull
    public List<String> getNoteList() {
        List<String> out = new ArrayList<>();
        if (notes == null || notes.isEmpty()) {
            return out;
        }
        for (String part : notes.split("[\\u001F\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static List<String> splitMulti(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        String[] parts = raw.split(SEP);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** 「唐 · 李白」形式的作者署名，佚名时只显示朝代 */
    @NonNull
    public String getAuthorLabel() {
        String dynastyLabel = Dynasty.labelOf(dynasty);
        String name = authorName.isEmpty() || "0".equals(authorName) ? "佚名" : authorName;
        if (dynasty.isEmpty()) {
            return name;
        }
        return dynastyLabel + " · " + name;
    }

    /** 热度星级（0–5 星） */
    public int getStarCount() {
        int stars = score / 52;
        if (stars < 1) {
            stars = 1;
        }
        if (stars > 5) {
            stars = 5;
        }
        return stars;
    }

    @NonNull
    public String getStars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < getStarCount(); i++) {
            sb.append('★');
        }
        return sb.toString();
    }

    /** 热度百分比（0–100） */
    public int getHotPercent() {
        int percent = score * 100 / 255;
        if (percent < 0) {
            percent = 0;
        }
        if (percent > 100) {
            percent = 100;
        }
        return percent;
    }

    /** 详情页展示用完整标题（含词牌） */
    @NonNull
    public String getDisplayTitle() {
        if (rhythmic.isEmpty()) {
            return title;
        }
        return title;
    }

    @Override
    public String toString() {
        return title + " / " + getAuthorLabel();
    }

    /** 同一首作品即相等：按 {@link #identityKey()} 比，不看 id（换库后 id 会变） */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Poem)) {
            return false;
        }
        return identityKey().equals(((Poem) obj).identityKey());
    }

    @Override
    public int hashCode() {
        return identityKey().hashCode();
    }

    // ---------------------------------------------------------------- Parcelable

    protected Poem(Parcel in) {
        id = in.readLong();
        authorId = in.readLong();
        title = in.readString();
        rhythmic = in.readString();
        srcName = in.readString();
        body = in.readString();
        tags = in.readString();
        notes = in.readString();
        score = in.readInt();
        nChar = in.readInt();
        authorName = in.readString();
        dynasty = in.readString();
        strain = in.readString();
        translation = in.readString();
        appreciation = in.readString();
        favoriteAt = in.readLong();
        // 追加在末尾：Parcel 是本 App 自用的顺序协议，读写一致即可，
        // 新增字段放尾部就不必动前面已有的次序。
        uid = in.readString();
        introduction = in.readString();
        creativeBackground = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeLong(authorId);
        dest.writeString(title);
        dest.writeString(rhythmic);
        dest.writeString(srcName);
        dest.writeString(body);
        dest.writeString(tags);
        dest.writeString(notes);
        dest.writeInt(score);
        dest.writeInt(nChar);
        dest.writeString(authorName);
        dest.writeString(dynasty);
        dest.writeString(strain);
        dest.writeString(translation);
        dest.writeString(appreciation);
        dest.writeLong(favoriteAt);
        dest.writeString(uid);
        dest.writeString(introduction);
        dest.writeString(creativeBackground);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Poem> CREATOR = new Creator<Poem>() {
        @Override
        public Poem createFromParcel(Parcel in) {
            return new Poem(in);
        }

        @Override
        public Poem[] newArray(int size) {
            return new Poem[size];
        }
    };
}
