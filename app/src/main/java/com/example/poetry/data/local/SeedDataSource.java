package com.example.poetry.data.local;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.poetry.data.model.Author;
import com.example.poetry.data.model.Category;
import com.example.poetry.data.model.Poem;
import com.example.poetry.data.model.PoemKind;
import com.example.poetry.data.model.Theme;
import com.example.poetry.util.QueryNormalizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置占位数据源。
 * <p>
 * 当 {@code poetry.db} 尚未投放（或没拷进设备）时，整个 App 用这批内置诗词驱动，
 * 保证所有页面、空态、交互都能直接演示；一旦本地库可用，{@link com.example.poetry.data.PoetryRepository}
 * 会优先使用真实库。
 * <p>
 * 用户只需把 dist 目录里的 poetry.db 放到 {@link DatabaseProvider#recommendDir(android.content.Context)}
 * 即可自动切换到全量 34 万首。
 */
public final class SeedDataSource {

    private static final List<Poem> POEMS = new ArrayList<>();

    static {
        POEMS.add(poem(1, "静夜思", "", "李白", "tang", "tang-poem",
                "床前明月光，疑是地上霜。\n举头望明月，低头思故乡。",
                "疑：怀疑，好像。|举头：抬起头来。",
                "明亮的月光洒在床前，好像地上泛起了一层霜。抬起头望向明月，低下头便想起故乡。",
                "二十字写尽羁旅之思，「疑」与「举头」「低头」的动作把乡愁写得如在眼前。"));
        POEMS.add(poem(2, "春晓", "", "孟浩然", "tang", "tang-poem",
                "春眠不觉晓，处处闻啼鸟。\n夜来风雨声，花落知多少。",
                "晓：天亮。|闻：听见。",
                "春日贪睡不知不觉天已亮，到处都能听见鸟儿的啼叫。昨夜隐约听到风雨声，不知吹落了多少春花。",
                "以「不觉」写春困，以「知多少」写惜春，喜春与伤春在一首诗里自然交融。"));
        POEMS.add(poem(3, "登鹳雀楼", "", "王之涣", "tang", "tang-poem",
                "白日依山尽，黄河入海流。\n欲穷千里目，更上一层楼。",
                "依：依傍。|穷：穷尽。",
                "夕阳依傍着西山慢慢沉落，滔滔黄河朝着大海奔流。若想看到千里之外的风光，就要再登上一层楼。",
                "前两句写壮阔之景，后两句写进取之志，成为盛唐气象最凝练的注脚。"));
        POEMS.add(poem(4, "江雪", "", "柳宗元", "tang", "tang-poem",
                "千山鸟飞绝，万径人踪灭。\n孤舟蓑笠翁，独钓寒江雪。",
                "绝：断绝。|蓑笠：蓑衣与斗笠。",
                "群山之中飞鸟绝迹，所有小路上都没有人的踪影。只有一叶孤舟上的老翁，披着蓑衣在寒江雪中独钓。",
                "二十字营造出一个绝对寂静的世界，「独钓」二字是诗人孤高人格的自画像。"));
        POEMS.add(poem(5, "相思", "", "王维", "tang", "tang-poem",
                "红豆生南国，春来发几枝。\n愿君多采撷，此物最相思。",
                "采撷：采摘。|相思：思念。",
                "红豆生长在南方，春天来了会发出多少新枝？希望你多多采摘，它最能寄托相思之情。",
                "借红豆咏相思，语浅情深，后被谱曲传唱，成为最流行的唐诗之一。"));
        POEMS.add(poem(6, "将进酒", "", "李白", "tang", "tang-poem",
                "君不见黄河之水天上来，奔流到海不复回。\n"
                        + "君不见高堂明镜悲白发，朝如青丝暮成雪。\n"
                        + "人生得意须尽欢，莫使金樽空对月。\n"
                        + "天生我材必有用，千金散尽还复来。",
                "高堂：高大的厅堂。|青丝：黑发。|樽：酒杯。",
                "你可曾见黄河之水从天而降，奔流入海再不回头？你可曾见高堂明镜中悲叹白发，早晨还是青丝傍晚已成雪白？人生得意就该尽情欢乐，别让金杯空对明月。天生我材必定有用，千金散尽还能再来。",
                "以黄河起兴，把生命短暂与豪情壮志并置，「天生我材必有用」是李白最响亮的自信宣言。"));
        POEMS.add(poem(7, "水调歌头", "水调歌头", "苏轼", "song", "song-ci",
                "明月几时有？把酒问青天。\n不知天上宫阙，今夕是何年。\n"
                        + "我欲乘风归去，又恐琼楼玉宇，高处不胜寒。\n起舞弄清影，何似在人间。\n"
                        + "转朱阁，低绮户，照无眠。\n不应有恨，何事长向别时圆？\n"
                        + "人有悲欢离合，月有阴晴圆缺，此事古难全。\n但愿人长久，千里共婵娟。",
                "宫阙：宫殿。|绮户：雕花的门窗。|婵娟：明月。",
                "明月是从什么时候开始有的？我端起酒杯问向苍天。不知天上的宫殿里，今夜是哪一年。我想乘风归去，又怕琼楼玉宇太高太冷。起舞时与影子嬉戏，天上哪里比得上人间！",
                "中秋词之绝唱。以月之圆缺照人之离合，最后落到「但愿人长久」的普世祝愿，豁达而深情。"));
        POEMS.add(poem(8, "念奴娇·赤壁怀古", "念奴娇", "苏轼", "song", "song-ci",
                "大江东去，浪淘尽，千古风流人物。\n故垒西边，人道是，三国周郎赤壁。\n"
                        + "乱石穿空，惊涛拍岸，卷起千堆雪。\n江山如画，一时多少豪杰。\n"
                        + "遥想公瑾当年，小乔初嫁了，雄姿英发。\n羽扇纶巾，谈笑间，樯橹灰飞烟灭。\n"
                        + "故国神游，多情应笑我，早生华发。\n人生如梦，一尊还酹江月。",
                "故垒：旧时的营垒。|纶巾：青丝带做的头巾。|樯橹：战船。|酹：洒酒祭奠。",
                "大江滚滚东流，浪涛淘尽了千古以来的英雄人物。旧营垒西边，人们说那就是三国周瑜破曹的赤壁。乱石直插天空，惊涛拍打江岸，卷起千万堆雪白的浪花。",
                "豪放派代表作。以江山形胜衬英雄业绩，又以「人生如梦」收束，豪迈中见旷达。"));
        POEMS.add(poem(9, "声声慢", "声声慢", "李清照", "song", "song-ci",
                "寻寻觅觅，冷冷清清，凄凄惨惨戚戚。\n乍暖还寒时候，最难将息。\n"
                        + "三杯两盏淡酒，怎敌他、晚来风急！\n雁过也，正伤心，却是旧时相识。\n"
                        + "满地黄花堆积，憔悴损，如今有谁堪摘？\n守着窗儿，独自怎生得黑！\n"
                        + "梧桐更兼细雨，到黄昏、点点滴滴。\n这次第，怎一个愁字了得！",
                "将息：调养休息。|次第：光景、情形。",
                "我四处寻觅，只觉冷清凄凉。乍暖还寒的时节，最难调养休息。几杯淡酒，怎能抵挡傍晚的急风！大雁飞过，正令人伤心，原来是从前的旧相识。",
                "连用十四叠字开篇，把丧乱之后的孤寂写得层层递进，是婉约词的巅峰之作。"));
        POEMS.add(poem(10, "天净沙·秋思", "天净沙", "马致远", "yuan", "yuan-qu",
                "枯藤老树昏鸦，\n小桥流水人家，\n古道西风瘦马。\n夕阳西下，\n断肠人在天涯。",
                "昏鸦：黄昏时的乌鸦。|断肠人：伤心至极的人。",
                "干枯的藤蔓、苍老的树上停着黄昏的乌鸦；小桥下流水潺潺，旁边有几户人家；古道上西风凛冽，一匹瘦马驮着游子。夕阳西沉，伤心断肠的人还漂泊在天涯。",
                "前三句十八字并列九个意象，不着一个动词而秋意全出，被誉为「秋思之祖」。"));
        POEMS.add(poem(11, "山坡羊·潼关怀古", "山坡羊", "张养浩", "yuan", "yuan-qu",
                "峰峦如聚，波涛如怒，山河表里潼关路。\n望西都，意踌躇。\n"
                        + "伤心秦汉经行处，宫阙万间都做了土。\n兴，百姓苦；亡，百姓苦。",
                "山河表里：外有黄河、内有华山。|踌躇：犹豫、感慨。",
                "山峰像聚拢在一起，波涛像在发怒，潼关外有黄河内有华山。遥望长安，心中感慨万千。走过秦汉故地令人伤心，万间宫殿都化作了尘土。",
                "末句「兴，百姓苦；亡，百姓苦」八个字道尽封建王朝兴亡的残酷真相，振聋发聩。"));
        POEMS.add(poem(12, "阿房宫赋", "", "杜牧", "tang", "tang-fu",
                "六王毕，四海一，蜀山兀，阿房出。\n覆压三百余里，隔离天日。\n"
                        + "骊山北构而西折，直走咸阳。\n二川溶溶，流入宫墙。\n"
                        + "五步一楼，十步一阁；廊腰缦回，檐牙高啄。",
                "六王：齐楚燕韩赵魏六国之君。|兀：光秃。|缦回：萦绕曲折。",
                "六国灭亡，天下统一，蜀山树木砍光，阿房宫才得以建成。它覆盖了三百多里地，遮天蔽日。从骊山北边建起再向西折，一直通到咸阳。",
                "借阿房宫的兴废讽喻时政，铺陈排比极尽辞赋之能事，末段「后人哀之而不鉴之」直指人心。"));
        POEMS.add(poem(13, "关雎", "", "佚名", "preqin", "han-poem",
                "关关雎鸠，在河之洲。\n窈窕淑女，君子好逑。\n"
                        + "参差荇菜，左右流之。\n窈窕淑女，寤寐求之。\n"
                        + "求之不得，寤寐思服。\n悠哉悠哉，辗转反侧。",
                "关关：水鸟和鸣声。|窈窕：文静美好。|逑：配偶。|寤寐：醒与睡。",
                "关关和鸣的雎鸠，栖息在河中的小洲。文静美好的姑娘，是君子的好配偶。长短不齐的荇菜，在水中左右漂浮。文静美好的姑娘，我日夜都想追求她。",
                "《诗经》开篇之作，以雎鸠和鸣起兴，写君子对淑女的爱慕与思念，温柔敦厚。"));
        POEMS.add(poem(14, "蒹葭", "", "佚名", "preqin", "han-poem",
                "蒹葭苍苍，白露为霜。\n所谓伊人，在水一方。\n"
                        + "溯洄从之，道阻且长。\n溯游从之，宛在水中央。",
                "蒹葭：芦苇。|溯洄：逆流而上。|溯游：顺流而下。",
                "芦苇苍苍茂密，白露凝结成霜。我思念的那个人，就站在河水的另一边。逆流而上去追寻她，道路艰险又漫长；顺流而下去追寻她，她仿佛就在水的中央。",
                "三章复沓，意境朦胧。「在水一方」成了中国文学里最美的距离。"));
        POEMS.add(poem(15, "木兰诗", "", "佚名", "preqin", "han-poem",
                "唧唧复唧唧，木兰当户织。\n不闻机杼声，惟闻女叹息。\n"
                        + "问女何所思，问女何所忆。\n女亦无所思，女亦无所忆。\n"
                        + "昨夜见军帖，可汗大点兵，军书十二卷，卷卷有爷名。",
                "唧唧：织布机声。|机杼：织布机。|军帖：征兵的文书。",
                "织机唧唧又唧唧，木兰对着门织布。听不见织机的声音，只听见木兰的叹息。问她在想什么，问她在惦记什么，她回答说并没有想什么。",
                "北朝民歌代表作，塑造了替父从军的木兰形象，叙事质朴生动，是乐府诗的双璧之一。"));
    }

    private SeedDataSource() {
    }

    @NonNull
    public static List<Poem> all() {
        return new ArrayList<>(POEMS);
    }

    @NonNull
    public static List<Poem> featured(int limit) {
        List<Poem> pool = new ArrayList<>(POEMS);
        // 用当天日期做稳定偏移，模拟「每日推荐」
        int offset = (int) (System.currentTimeMillis() / 86_400_000L % pool.size());
        List<Poem> out = new ArrayList<>();
        for (int i = 0; i < pool.size() && out.size() < limit; i++) {
            out.add(pool.get((offset + i) % pool.size()));
        }
        return out;
    }

    @Nullable
    public static Poem poemById(long id) {
        for (Poem poem : POEMS) {
            if (poem.getId() == id) {
                return poem;
            }
        }
        return null;
    }

    @NonNull
    public static List<Poem> search(@NonNull String keyword, int limit) {
        String needle = QueryNormalizer.normalize(keyword);
        List<Poem> out = new ArrayList<>();
        if (needle.isEmpty()) {
            return out;
        }
        for (Poem poem : POEMS) {
            String haystack = QueryNormalizer.normalize(
                    poem.getTitle() + poem.getAuthorName() + poem.getBody());
            if (haystack.contains(needle)) {
                out.add(poem);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    @NonNull
    public static List<Poem> listByKind(@NonNull PoemKind kind, int limit) {
        List<Poem> out = new ArrayList<>();
        for (Poem poem : POEMS) {
            if (poem.getKind() == kind) {
                out.add(poem);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    @NonNull
    public static List<Poem> listByDynasty(@NonNull String dynastyCode, int limit) {
        List<Poem> out = new ArrayList<>();
        for (Poem poem : POEMS) {
            if (dynastyCode.equals(poem.getDynasty())) {
                out.add(poem);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    @NonNull
    public static List<Poem> listByAuthor(long authorId, int limit) {
        List<Poem> out = new ArrayList<>();
        for (Poem poem : POEMS) {
            if (poem.getAuthorId() == authorId) {
                out.add(poem);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 统计

    @NonNull
    public static List<Category> kindCategories() {
        Map<String, Long> counts = new HashMap<>();
        for (Poem poem : POEMS) {
            String code = poem.getKind().getCode();
            Long value = counts.get(code);
            counts.put(code, value == null ? 1L : value + 1L);
        }
        List<Category> list = new ArrayList<>();
        for (PoemKind kind : PoemKind.values()) {
            Category category = new Category(kind.getCode(), kind.getLabel(), kind.getDesc());
            Long count = counts.get(kind.getCode());
            category.setCount(count == null ? 0L : count);
            category.setColorRes(kind.getColorRes());
            category.setLightColorRes(kind.getLightColorRes());
            category.setMark(kind.getMark());
            list.add(category);
        }
        return list;
    }

    @NonNull
    public static List<Category> dynastyCategories() {
        Map<String, Long> counts = new HashMap<>();
        for (Poem poem : POEMS) {
            Long value = counts.get(poem.getDynasty());
            counts.put(poem.getDynasty(), value == null ? 1L : value + 1L);
        }
        List<Category> list = new ArrayList<>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            Category category = new Category(entry.getKey(),
                    com.example.poetry.data.model.Dynasty.labelOf(entry.getKey()), "");
            category.setCount(entry.getValue());
            list.add(category);
        }
        return list;
    }

    @NonNull
    public static List<Category> themeCategories() {
        List<Category> list = new ArrayList<>();
        for (Theme theme : Theme.defaults()) {
            Category category = new Category(theme.keyword, theme.label, "");
            category.setCount(search(theme.keyword, 50).size());
            list.add(category);
        }
        return list;
    }

    @NonNull
    public static List<Author> topAuthors(int limit) {
        Map<String, Author> map = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Poem poem : POEMS) {
            Author author = map.get(poem.getAuthorName());
            if (author == null) {
                author = new Author();
                author.setId(poem.getAuthorId());
                author.setName(poem.getAuthorName());
                author.setDynasty(poem.getDynasty());
                map.put(poem.getAuthorName(), author);
            }
            Integer count = counts.get(poem.getAuthorName());
            counts.put(poem.getAuthorName(), count == null ? 1 : count + 1);
        }
        List<Author> list = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Author author = map.get(entry.getKey());
            author.setNPoems(entry.getValue());
            list.add(author);
        }
        java.util.Collections.sort(list, (a, b) -> b.getNPoems() - a.getNPoems());
        if (list.size() > limit) {
            return new ArrayList<>(list.subList(0, limit));
        }
        return list;
    }

    // ------------------------------------------------------------ 构造

    private static Poem poem(long id, String title, String rhythmic, String author, String dynasty,
                             String srcName, String body, String notes,
                             String translation, String appreciation) {
        Poem poem = new Poem();
        poem.setId(id);
        // 种子数据里作者 id 与作品 id 保持一致，便于「按作者」过滤
        poem.setAuthorId(id);
        poem.setTitle(title);
        poem.setRhythmic(rhythmic);
        poem.setAuthorName(author);
        poem.setDynasty(dynasty);
        poem.setSrcName(srcName);
        poem.setBody(body);
        poem.setNotes(notes.replace("|", Poem.SEP));
        poem.setTranslation(translation);
        poem.setAppreciation(appreciation);
        poem.setScore(220);
        poem.setNChar(body.replace("\n", "").length());
        return poem;
    }
}
