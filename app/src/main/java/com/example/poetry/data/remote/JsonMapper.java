package com.example.poetry.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.model.Author;
import com.example.poetry.data.model.Category;
import com.example.poetry.data.model.Page;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.PoemKind;
import com.example.poetry.data.model.UserAuth;
import com.example.poetry.data.model.Voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 后端 JSON → 客户端模型。
 *
 * <p>单独一个类而不是散在各个 api 方法里，是因为这里有一批**容易搞错的字段语义**，
 * 集中放才看得清：
 *
 * <ul>
 *   <li><b>{@code dynastyKey} ↔ {@code dynasty}</b>：接口给了两个朝代字段。
 *       {@code dynasty}（如「唐」）是从 {@code poem_id} 高位反解的中文；
 *       {@code dynastyKey}（如 {@code tang}）来自 {@code authors.dynasty}。
 *       客户端要的是 **key** —— {@code Poem.dynasty} 存的是 code，
 *       展示时才用 {@code Dynasty.labelOf()} 翻中文。塞中文进去会全变成「未详」。</li>
 *   <li><b>{@code source} ↔ {@code kind}</b>：同理。接口的 {@code kind} 是
 *       「诗/词/曲/文/经/论著」（id 高位），客户端的 {@code PoemKind} 只认
 *       「诗/词/曲/赋/民歌」且由 {@code sources.name} 反解。所以必须用 {@code source}，
 *       不能用 {@code kind} —— 否则「文」「经」「论著」全被归成「诗」。</li>
 *   <li><b>列表 vs 详情</b>：列表接口不带 {@code body}，扫出来就是空串。
 *       这不是缺失，是刻意的（见后端 {@code PoemBrief} 的注释）；
 *       详情页靠 {@code getBody().isEmpty()} 判断要不要补一次详情请求。</li>
 *   <li><b>{@code tags}</b>：接口给 JSON 数组，客户端模型用 {@code \u001F} 拼接的字符串
 *       （{@link Poem#SEP}），因为 {@link Poem#getTagList()} 和 Parcelable 都按那个格式走。</li>
 * </ul>
 */
final class JsonMapper {

    private JsonMapper() {
    }

    // ------------------------------------------------------------ 作品

    @NonNull
    static List<Poem> toPoems(@Nullable JSONArray array) {
        List<Poem> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                out.add(toPoem(item));
            }
        }
        return out;
    }

    /**
     * 从分页负载 {@code {total,page,size,items}} 里取出作品，**连 {@code total} 一起**
     * （列表分页要用它判断「还有没有下一页」）。
     *
     * <p>没给 {@code total} 的接口（如 {@code /v1/authors/:ref/poems} 只回
     * {@code {author,items,page,size}}）落到 {@link Page#TOTAL_UNKNOWN}，
     * 由 {@link Page#hasMore()} 退化成「本页是否装满」。
     *
     * <p>{@code page}/{@code size} 以请求参数为准，不采信响应体：这两个值调用方自己最清楚，
     * 而后端某些接口回的是默认值，拿来会和实际请求的页号对不上。
     */
    @NonNull
    static Page<Poem> toPageOfPoems(@Nullable Object data, int page, int size) {
        JSONObject o = ApiClient.asObject(data);
        List<Poem> items = toPoems(o.optJSONArray("items"));
        return new Page<>(items, o.optLong("total", Page.TOTAL_UNKNOWN), page, size);
    }

    @NonNull
    static Poem toPoem(@NonNull JSONObject o) {
        Poem poem = new Poem();
        poem.setId(o.optLong("id", 0L));
        poem.setUid(o.optString("uid", ""));
        poem.setTitle(o.optString("title", ""));
        poem.setAuthorName(o.optString("authorName", ""));
        // 注意：用 dynastyKey（code）而不是 dynasty（中文），理由见类注释
        poem.setDynasty(o.optString("dynastyKey", ""));
        // 注意：用 source 而不是 kind，理由见类注释
        poem.setSrcName(o.optString("source", ""));
        poem.setRhythmic(o.optString("rhythmic", ""));
        poem.setTags(joinTags(o.optJSONArray("tags")));
        poem.setScore(o.optInt("score", 0));
        poem.setNChar(o.optInt("nChar", 0));
        poem.setNotes(o.optString("notes", ""));
        // 列表接口没有 body，但带 excerpt（服务端截好的前两行）——卡片就靠它显示摘要。
        // 别把它并进 body：详情页用 body.isEmpty() 判断要不要补一次详情请求。
        poem.setExcerptFromServer(o.optString("excerpt", ""));

        // 以下字段只有详情接口才有；列表接口扫出来是空串，属预期
        poem.setBody(o.optString("body", ""));
        poem.setTranslation(o.optString("translation", ""));
        poem.setIntroduction(o.optString("introduction", ""));
        poem.setCreativeBackground(o.optString("creativeBackground", ""));
        poem.setAppreciation(o.optString("appreciation", ""));
        // 平仄也随详情一起回来（服务端做的合并），列表接口没有这个字段，扫出来是空串
        poem.setStrain(o.optString("strain", ""));
        return poem;
    }

    @Nullable
    static Poem toPoemOrNull(@Nullable Object data) {
        JSONObject o = data instanceof JSONObject ? (JSONObject) data : null;
        return o == null ? null : toPoem(o);
    }

    /** 把 {@code ["a","b"]} 拼成 {@code a\u001Fb}，与 {@link Poem#getTagList()} 的约定一致。 */
    @NonNull
    private static String joinTags(@Nullable JSONArray tags) {
        if (tags == null || tags.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tags.length(); i++) {
            String tag = tags.optString(i, "").trim();
            if (tag.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(Poem.SEP);
            }
            sb.append(tag);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 作者

    @NonNull
    static List<Author> toAuthors(@Nullable JSONArray array) {
        List<Author> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                out.add(toAuthor(item));
            }
        }
        return out;
    }

    @NonNull
    static Author toAuthor(@NonNull JSONObject o) {
        Author author = new Author();
        author.setId(o.optLong("id", 0L));
        author.setUid(o.optString("uid", ""));
        author.setName(o.optString("name", ""));
        author.setDesc(o.optString("desc", ""));
        // Author.dynasty 同样是 code：后端的 dynasty 是 code，dynastyCn 才是中文
        author.setDynasty(o.optString("dynasty", ""));
        // nPoems 后端现算（不读 authors.n_poems 那个母库篇数），可以直接用
        author.setNPoems(o.optInt("nPoems", 0));
        return author;
    }

    @Nullable
    static Author toAuthorOrNull(@Nullable Object data) {
        JSONObject o = data instanceof JSONObject ? (JSONObject) data : null;
        return o == null ? null : toAuthor(o);
    }

    // ------------------------------------------------------------ 分类

    /**
     * 体裁卡：后端只给 {@code key/label/desc/count}，配色与水印字是客户端的视觉资产，
     * 从 {@link PoemKind} 按 code 补上 —— 这样「诗」永远是这个色，不随后端改。
     */
    @NonNull
    static List<Category> toKindCategories(@Nullable JSONArray array) {
        List<Category> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String code = o.optString("key", "");
            PoemKind kind = PoemKind.fromCode(code);
            Category category = new Category(code, o.optString("label", kind.getLabel()),
                    o.optString("desc", kind.getDesc()));
            category.setCount(o.optLong("count", 0L));
            category.setColorRes(kind.getColorRes());
            category.setLightColorRes(kind.getLightColorRes());
            category.setMark(kind.getMark());
            out.add(category);
        }
        return out;
    }

    /** 朝代宫格：后端按计数给行，顺序由客户端按 {@code Dynasty.values()} 重排（宫格顺序固定）。 */
    @NonNull
    static List<Category> toDynastyCategories(@Nullable JSONArray array) {
        List<Category> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            long count = o.optLong("count", 0L);
            if (count <= 0) {
                continue;   // 与本地库一致：0 首的朝代不出现在宫格里
            }
            Category category = new Category(o.optString("key", ""), o.optString("label", ""), "");
            category.setCount(count);
            out.add(category);
        }
        return out;
    }

    // ------------------------------------------------------------ 音色

    @NonNull
    static List<Voice> toVoices(@Nullable JSONArray array) {
        List<Voice> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Voice voice = new Voice();
            voice.setId(o.optString("id", ""));
            voice.setName(o.optString("name", ""));
            voice.setDesc(o.optString("desc", ""));
            voice.setTag1(o.optString("tag1", ""));
            voice.setTag2(o.optString("tag2", ""));
            voice.setRate((float) o.optDouble("rate", 1.0));
            voice.setPitch((float) o.optDouble("pitch", 1.0));
            voice.setLocale(o.optString("locale", "zh-CN"));
            voice.setCloud(o.optBoolean("cloud", true));
            voice.setLocked(o.optBoolean("locked", false));
            voice.setSelected(false);
            out.add(voice);
        }
        return out;
    }

    // ------------------------------------------------------------ 账号

    /**
     * 认证绑定列表（{@code GET /v1/user/auth}）。
     *
     * <p>接口回的是顶层数组（{@code {code,data:[...]}}），不是分页负载，所以用
     * {@link ApiClient#asArray} 取，别走 {@link #toPageOfPoems} 那条路。
     */
    @NonNull
    static List<UserAuth> toAuths(@Nullable JSONArray array) {
        List<UserAuth> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                out.add(UserAuth.fromJson(item));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 杂项标量

    /** 热门搜索词：接口给 {@code [{keyword,count,order}]}，客户端只要词。 */
    @NonNull
    static List<String> toKeywords(@Nullable JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String keyword = o.optString("keyword", "").trim();
            if (!keyword.isEmpty()) {
                out.add(keyword);
            }
        }
        return out;
    }
}
