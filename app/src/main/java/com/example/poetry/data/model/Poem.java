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
 * 字段直接映射 {@code poetry.db → poems} 表：
 * id / author_id / title / rhythmic_id / src_id / body / tags / notes / score / n_char，
 * 另外把联表得到的作者名、词牌名、数据源名、朝代一起带出来，避免 UI 层再查一次。
 * <p>
 * 说明：
 * <ul>
 *   <li>{@code body} 为简体、无空格文本，行与行之间用 '\n' 分隔；</li>
 *   <li>{@code tags} / {@code notes} 使用 ASCII 单元分隔符 \u001F 分隔多项；</li>
 *   <li>{@code score} 为 0–255 的热度分，用于「精选 / 热度」排序与星级展示；</li>
 *   <li>{@code strain} 为平仄串（来自可选的 poetry-strains.db）；</li>
 *   <li>{@code translation} / {@code appreciation} 由后台接口下发，未接入时为空。</li>
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

    /** 译文：后台接口预留字段 */
    private String translation;
    /** 赏析：后台接口预留字段 */
    private String appreciation;

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
        this.translation = "";
        this.appreciation = "";
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

    /** 注释列表（译文 / 赏析为空时，UI 用注释兜底） */
    @NonNull
    public List<String> getNoteList() {
        return splitMulti(notes);
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

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Poem)) {
            return false;
        }
        return id == ((Poem) obj).id;
    }

    @Override
    public int hashCode() {
        return (int) (id ^ (id >>> 32));
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
