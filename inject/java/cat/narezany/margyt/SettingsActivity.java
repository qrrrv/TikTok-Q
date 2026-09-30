package cat.narezany.margyt;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * MargyT's own screen, drawn in TikTok's settings language.
 *
 * Page, cards, title, grey section labels: the shapes are TikTok's, and so are
 * the colours -- not copied out of a screenshot but the ones the row measured
 * off the real settings screen and kept. Opened from the launcher with TikTok
 * never having been on screen, it falls back to plain black or white.
 *
 * Every view is built here in code. Adding a layout or a style would mean
 * adding resources, and adding resources means rewriting a 25 MB resource
 * table -- the one thing this build refuses to do.
 */
public class SettingsActivity extends Activity {

    private Skin skin;
    private FrameLayout root;
    private LinearLayout column;
    private View restartBar;

    /**
     * Whether a setting was changed in this process.
     *
     * Static, because the bar is about the process and not about the screen:
     * leaving the settings and coming back does not un-change what was changed,
     * and only a restart -- which is a new process, where this is false again --
     * does.
     */
    private static boolean pending;

    /** Where the mod, its people and its money live. */
    private static final String CHANNEL = "https://t.me/margytiktok";
    private static final String FORUM = "https://t.me/margeletforum";
    private static final String OWNER_TELEGRAM = "https://t.me/narezany";

    /** Where a receipt goes: the channel's own messages, not somebody's inbox. */
    private static final String CHANNEL_WRITE = "https://t.me/margytiktok?direct";
    private static final String OWNER_TIKTOK =
            "https://tiktok.com/@narezany?_r=1&_t=ZT-99hPDJ26hji_";
    private static final String HELPER = "https://www.tiktok.com/@MS4wLjABAAAApBE7v5"
            + "y_tClqKlwqBpZNwzIBn1K7aRJLDxegPPnx8joas1EmS8NZpVFdWATb4zGf";
    private static final String GITHUB = "https://git.narez.xyz/narezany/MargyT";
    private static final String DOCS =
            "https://git.narez.xyz/narezany/MargyT/src/branch/main/docs/plugins.md";
    private static final String YOOMONEY = "https://yoomoney.ru/to/4100118196133693";
    private static final String CARD_NUMBER = "2204120143055305";

    private boolean countriesOpen;
    private boolean accentOpen;
    private boolean textOpen;
    private boolean backgroundOpen;
    private boolean fontOpen;
    private boolean iconOpen;
    private ScrollView page;
    private View donateAnchor;
    private boolean thanksOpen;
    private boolean mineOpen;
    private java.util.List<Mine.Held> ordering;

    /** Set once the badges have been rearranged by hand and not yet saved. */
    private boolean rearranged;
    private boolean streakOpen;
    private boolean diaryOpen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Margy.attach(this);
        skin = Skin.remembered(this);
        dressTheWindow();
        maybeAskForSupport();
        // the list may still be the one cached before this screen existed, so
        // redraw when the server answers rather than waiting for a tap
        Badges.tell(this::rebuild);
        // a test build is only for the people who paid for it, and whether
        // this account is one of them is a thing the server knows -- so the
        // refusal waits for its answer rather than firing on a cold start,
        // when the list of badges is simply not here yet
        Mine.ask(() -> {
            rebuild();
            if (Tester.on() && !Tester.allowed()) Tester.refuse(this);
        });
        if (Tester.on() && Tester.asked() && !Tester.allowed()) Tester.refuse(this);

        ScrollView scroll = page = new ScrollView(this);
        scroll.setBackgroundColor(skin.page);
        scroll.setFillViewport(true);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, statusBar(), 0, dp(32));
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root = new FrameLayout(this);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private void dressTheWindow() {
        try {
            getWindow().setStatusBarColor(skin.page);
            getWindow().setNavigationBarColor(skin.page);
            if (!skin.dark()) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------- the screen

    private void rebuild() {
        column.removeAllViews();
        column.addView(backArrow());
        column.addView(title("MargyT"));

        if (Badges.freeStillOpen() && !Mine.hasFree()) column.addView(freeBanner());
        column.addView(donateBanner());

        column.addView(section(Text.REGION));
        LinearLayout head = card();
        head.addView(switchRow());
        column.addView(wrap(head));

        column.addView(section(Text.COUNTRY));
        LinearLayout countries = card();
        countries.addView(countryHead());
        if (countriesOpen) {
            countries.addView(line());
            for (String[] country : Margy.COUNTRIES) {
                countries.addView(appear(countryRow(country)));
            }
        }
        column.addView(wrap(countries));

        column.addView(section(Text.ACCENT));
        LinearLayout accent = card();
        accent.addView(accentHead());
        if (accentOpen) {
            accent.addView(line());
            accent.addView(appear(palette()));
            if (Accent.fromWallpaper() != 0) {
                accent.addView(line());
                accent.addView(wallpaperRow());
            }
        }
        column.addView(wrap(accent));

        column.addView(section(Text.THEME));
        LinearLayout theme = card();
        theme.addView(toggleRow("contrast", Text.THEME_ON, Themes.isEnabled(), on -> {
            Themes.setEnabled(on);
            rebuild();
        }));
        if (Themes.isEnabled()) {
            theme.addView(line());
            theme.addView(toggleRow("wallpaper", Text.THEME_MATERIAL,
                    Themes.isMaterial(), on -> {
                        Themes.setMaterial(on);
                        rebuild();
                    }));
            if (!Themes.isMaterial()) {
                theme.addView(line());
                theme.addView(shadeHead(true));
                if (textOpen) {
                    theme.addView(line());
                    theme.addView(appear(shades(true)));
                }
                theme.addView(line());
                theme.addView(shadeHead(false));
                if (backgroundOpen) {
                    theme.addView(line());
                    theme.addView(appear(shades(false)));
                }
                theme.addView(line());
                theme.addView(appear(strengthRow()));
            }
            theme.addView(line());
            theme.addView(quiet(Text.THEME_NOTE));
        }
        column.addView(wrap(theme));

        column.addView(section(Text.FONT));
        LinearLayout fonts = card();
        fonts.addView(fontHead());
        if (fontOpen) {
            fonts.addView(line());
            fonts.addView(appear(fontChoices()));
        }
        column.addView(wrap(fonts));

        column.addView(section(Text.ICON));
        LinearLayout icons = card();
        icons.addView(iconHead());
        if (iconOpen) {
            icons.addView(line());
            icons.addView(appear(iconChoices()));
        }
        column.addView(wrap(icons));

        column.addView(section(Text.FEED));
        LinearLayout feed = card();
        feed.addView(toggleRow("block", Text.HIDE_ADS, Feed.isEnabled(), Feed::setEnabled));
        feed.addView(line());
        feed.addView(toggleRow("visibility_off", Text.HIDE_LIVE, Feed.hides(Feed.KEY_LIVE),
                on -> Feed.setHides(Feed.KEY_LIVE, on)));
        feed.addView(line());
        feed.addView(toggleRow("image", Text.HIDE_PHOTOS, Feed.hides(Feed.KEY_PHOTOS),
                on -> Feed.setHides(Feed.KEY_PHOTOS, on)));
        column.addView(wrap(feed));

        column.addView(section(Text.VIDEO));
        LinearLayout video = card();
        video.addView(toggleRow("volume_up", Text.SOUND, Sound.isEnabled(), Sound::setEnabled));
        video.addView(line());
        video.addView(toggleRow("timeline", Text.SEEKBAR, Seekbar.isEnabled(),
                Seekbar::setEnabled));
        video.addView(line());
        video.addView(toggleRow("info", Text.ALWAYS_DATE, Dates.isEnabled(),
                Dates::setEnabled));
        video.addView(caption(Text.ALWAYS_DATE_NOTE));
        video.addView(line());
        video.addView(betaRow("visibility_off", Text.DIM, Dim.isEnabled(), on -> {
            Dim.setEnabled(on);
            rebuild();
        }));
        if (Dim.isEnabled()) {
            video.addView(line());
            video.addView(slider(Text.DIM_HOW, Dim.strength(), 90, value -> {
                Dim.setStrength(value);
                markChanged();
            }));
            video.addView(quiet(Text.DIM_NOTE));
        }
        video.addView(line());
        video.addView(toggleRow("hdr_off", Text.NO_HDR, Hdr.isEnabled(), on -> {
            Hdr.setEnabled(on);
            markChanged();
        }));
        video.addView(caption(Text.NO_HDR_NOTE));
        if (!Hdr.reachable()) video.addView(quiet(Text.NO_HDR_OLD));
        column.addView(wrap(video));

        column.addView(section(Text.GRADIENT));
        column.addView(wrap(gradientCard()));

        column.addView(section(Text.TAGS));
        LinearLayout tags = card();
        tags.addView(betaRow("tag", Text.TAGS, Tags.isEnabled(), Tags::setEnabled));
        java.util.List<String> blocked = Tags.all();
        if (blocked.isEmpty()) {
            tags.addView(caption(Text.TAGS_NONE));
        } else {
            for (final String tag : blocked) {
                tags.addView(line());
                tags.addView(actionRow("block", "#" + tag, null, () -> {
                    Tags.remove(tag);
                    rebuild();
                }));
            }
        }
        tags.addView(line());
        tags.addView(actionRow("tag", Text.TAGS_ADD, null, () -> Popup.write(
                this, Text.TAGS_ADD, "", Text.TAGS_ADD, said -> {
                    if (!Tags.add(said)) {
                        Toast.makeText(this, Text.TAGS_FULL, Toast.LENGTH_SHORT).show();
                    }
                    rebuild();
                })));
        tags.addView(caption(Text.TAGS_NOTE));
        column.addView(wrap(tags));

        column.addView(section(Text.BANNER));
        column.addView(wrap(bannerCard()));

        column.addView(section(Text.FPS));
        LinearLayout fps = card();
        fps.addView(quiet(Text.FPS_ABOUT));
        String rate = Rate.name();
        for (int i = 0; i < Rate.CHOICES.length; i++) {
            final String which = Rate.CHOICES[i];
            if (i > 0) fps.addView(line());
            fps.addView(pickRow(fpsName(which), which.equals(rate), null, () -> {
                Rate.choose(which);
                markChanged();
            }));
        }
        if (!Rate.reachable(this)) fps.addView(quiet(Text.FPS_UNSUPPORTED));
        column.addView(wrap(fps));

        column.addView(section(Text.HIDDEN));
        LinearLayout hidden = card();
        hidden.addView(toggleRow("mic", Text.VOICE, Flags.isOn(Flags.KEY_VOICE),
                on -> Flags.set(Flags.KEY_VOICE, on)));
        column.addView(wrap(hidden));
        column.addView(caption(Text.HIDDEN_NOTE));

        column.addView(section(Text.DOWNLOADS));
        LinearLayout downloads = card();
        downloads.addView(toggleRow("image", Text.NO_WATERMARK, Download.isEnabled(),
                Download::setEnabled));
        downloads.addView(line());
        downloads.addView(toggleRow("download", Text.DOWNLOAD_ALWAYS, Download.isAlways(),
                Download::setAlways));
        downloads.addView(line());
        downloads.addView(toggleRow("place", Text.SAVE_AVATARS_ON, Avatars.isEnabled(),
                Avatars::setEnabled));
        downloads.addView(line());
        downloads.addView(toggleRow("star", Text.SAVE_STICKERS_ON, Stickers.isEnabled(),
                Stickers::setEnabled));
        column.addView(wrap(downloads));

        column.addView(section(Text.TEXTURES));
        LinearLayout textures = card();
        textures.addView(toggleRow("image", Text.TEXTURES_ON, Textures.isEnabled(), on -> {
            Textures.setEnabled(on);
            markChanged();
        }));
        textures.addView(line());
        textures.addView(actionRow("download", Text.TEXTURES_EXPORT,
                Text.TEXTURES_EXPORT_NOTE, () -> exportTextures(false)));
        textures.addView(line());
        textures.addView(actionRow("article", Text.TEXTURES_EXPORT_XML,
                Text.TEXTURES_EXPORT_XML_NOTE, () -> exportTextures(true)));
        textures.addView(line());
        textures.addView(actionRow("extension", Text.TEXTURES_INSTALL,
                Text.TEXTURES_INSTALL_NOTE, this::pickTextures));
        textures.addView(line());
        textures.addView(linkRow("article", Text.TEXTURES_DOCS,
                Text.TEXTURES_DOCS_NOTE, TEXTURE_DOCS));
        List<Textures.Pack> packs = Textures.installed(this);
        if (packs.isEmpty()) {
            textures.addView(line());
            textures.addView(quiet(Text.TEXTURES_NONE));
        } else {
            for (Textures.Pack one : packs) {
                textures.addView(line());
                textures.addView(packRow(one));
            }
        }
        column.addView(wrap(textures));
        column.addView(caption(Text.TEXTURES_NOTE));

        column.addView(section(Text.PLUGINS));
        LinearLayout plugins = card();
        plugins.addView(actionRow("extension", Text.STORE, Text.STORE_OPEN, () -> {
            try {
                startActivity(new Intent(this, StoreActivity.class));
            } catch (Throwable error) {
                Toast.makeText(this, String.valueOf(error), Toast.LENGTH_LONG).show();
            }
        }));
        plugins.addView(line());
        plugins.addView(installRow());
        plugins.addView(line());
        plugins.addView(linkRow("article", Text.PLUGIN_DOCS, Text.PLUGIN_DOCS_NOTE, DOCS));
        List<Plugins.Info> installed = Plugins.list();
        if (installed.isEmpty()) {
            plugins.addView(line());
            plugins.addView(quiet(Text.PLUGIN_NONE));
        } else {
            for (Plugins.Info info : installed) {
                plugins.addView(line());
                plugins.addView(pluginRow(info));
            }
        }
        column.addView(wrap(plugins));
        column.addView(caption(Text.PLUGIN_WARNING));

        column.addView(section(Text.STREAKS));
        LinearLayout streaks = card();
        streaks.addView(betaRow("repeat", Text.STREAK_AUTO, Streaks.isEnabled(),
                Streaks::setEnabled));
        streaks.addView(line());
        streaks.addView(stickerHead());
        if (streakOpen) {
            streaks.addView(line());
            streaks.addView(appear(stickerChoices()));
        }
        streaks.addView(line());
        streaks.addView(actionRow("play_circle", Text.STREAK_TEST, Text.STREAK_TEST_NOTE,
                () -> {
                    Streaks.test(this);
                    Toast.makeText(this, Text.STREAK_TEST_GOING, Toast.LENGTH_SHORT).show();
                }));
        column.addView(wrap(streaks));
        column.addView(caption(Text.STREAK_NOTE));

        column.addView(section(Text.LINKS));
        LinearLayout links = card();
        links.addView(linkRow("link", Text.CHANNEL, "@margytiktok", CHANNEL));
        links.addView(line());
        links.addView(linkRow("group", Text.FORUM, "@margeletforum", FORUM));
        links.addView(line());
        links.addView(linkRow("extension", Text.SOURCE, "narezany/MargyT", GITHUB));
        links.addView(line());
        links.addView(toggleRow("favorite_border", Text.BADGES_ON, Badges.isEnabled(),
                Badges::setEnabled));
        links.addView(line());
        links.addView(mineHead());
        if (mineOpen) links.addView(appear(mineRows()));
        links.addView(line());
        links.addView(thanksHead());
        if (thanksOpen) {
            links.addView(line());
            links.addView(appear(thanks()));
        }
        donateAnchor = wrap(links);
        column.addView(donateAnchor);

        column.addView(section(Text.ACCOUNT));
        LinearLayout account = card();
        account.addView(idRow(Text.ACCOUNT_ID, Account.id()));
        account.addView(line());
        account.addView(idRow(Text.ACCOUNT_SEC_ID, Account.secId()));
        column.addView(wrap(account));

        column.addView(section(Text.UPDATE));
        LinearLayout updates = card();
        updates.addView(actionRow("download", Text.UPDATE_CHECK,
                Updater.newer() ? Text.UPDATE_THERE_IS + " " + Updater.latest() : null,
                () -> Updater.check(this, true)));
        updates.addView(line());
        updates.addView(toggleRow("info", Text.UPDATE_REMIND, Updater.remind(this),
                on -> Updater.setRemind(this, on)));
        if (Updater.waiting(this)) {
            updates.addView(line());
            updates.addView(actionRow("extension", Text.UPDATE_INSTALL, null,
                    () -> Updater.install(this)));
        }
        updates.addView(line());
        updates.addView(toggleRow("bug_report", Text.PATCH_ON, Patch.wanted(this),
                on -> Patch.setWanted(this, on)));
        updates.addView(actionRow("download", Text.PATCH_CHECK,
                Patch.running().length() > 0
                        ? Text.PATCH_RUNNING + " " + Patch.running() : null,
                () -> Patch.check(this, (got, trouble) -> {
                    Screen.say(got ? Text.PATCH_GOT
                            : (trouble.length() > 0 ? trouble : Text.PATCH_NONE));
                    if (got) markChanged();
                    rebuild();
                })));
        if (Patch.running().length() > 0 || Patch.onShelf(this)) {
            updates.addView(actionRow("block", Text.PATCH_DROP, null, () -> {
                Patch.drop(this);
                markChanged();
                rebuild();
            }));
        }
        updates.addView(caption(Text.PATCH_NOTE));
        column.addView(wrap(updates));

        column.addView(section(Text.DIARY));
        LinearLayout diary = card();
        diary.addView(diaryHead());
        if (diaryOpen) {
            diary.addView(line());
            diary.addView(appear(diaryLines()));
        }
        column.addView(wrap(diary));

        column.addView(versions());

        showRestartBar();
    }

    /**
     * The bar that says a restart is due, pinned to the foot of the screen.
     *
     * It sits in the root frame rather than in the column, so it stays put
     * while the page scrolls under it, and it is built again on every rebuild
     * because the accent it is painted with may be what just changed.
     */
    private void showRestartBar() {
        if (restartBar != null) {
            root.removeView(restartBar);
            restartBar = null;
        }
        if (pending) {
            restartBar = restartBar();
            root.addView(restartBar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        }
        // the last card has to be able to clear the bar when it is there
        column.setPadding(0, statusBar(), 0,
                dp(32) + (pending ? dp(64) + navigationBar() : 0));
    }

    /** A setting changed: redraw, and from now on the bar is up. */
    private void markChanged() {
        pending = true;
        rebuild();
    }

    // ----------------------------------------------------------- the rows

    private View switchRow() {
        LinearLayout row = row();
        row.addView(icon("language"));
        row.addView(label(Text.CHANGE_REGION), grow());

        final M3Switch toggle = new M3Switch(this);
        toggle.colours(Accent.colour(), skin.muted(), skin.card);
        toggle.setChecked(Margy.isEnabled());
        toggle.setOnChanged(checked -> {
            Margy.setEnabled(checked);
            markChanged();
        });
        row.addView(toggle);

        row.setOnClickListener(v -> {
            toggle.setChecked(!toggle.isChecked(), true);
            Margy.setEnabled(toggle.isChecked());
            markChanged();
        });
        return sized(row, 56);
    }

    private View countryHead() {
        String[] current = Margy.current();
        LinearLayout row = row();
        row.addView(icon("place"));
        row.setAlpha(Margy.isEnabled() ? 1f : 0.4f);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(current[Margy.LABEL]));
        text.addView(detail(current[Margy.CARRIER] + "  ·  " + current[Margy.MCCMNC]
                + "  ·  " + current[Margy.ISO].toUpperCase(Locale.US)));
        row.addView(text, grow());

        TextView chevron = new TextView(this);
        chevron.setText(countriesOpen ? "⌃" : "⌄");
        chevron.setTextColor(skin.muted());
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        row.addView(chevron);

        if (Margy.isEnabled()) {
            row.setOnClickListener(v -> {
                countriesOpen = !countriesOpen;
                rebuild();
            });
        }
        return sized(row, 64);
    }

    private View countryRow(final String[] country) {
        boolean selected = country[Margy.ISO].equals(Margy.iso());
        LinearLayout row = row();
        row.addView(flag(country[Margy.ISO]));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(country[Margy.LABEL]));
        text.addView(detail(country[Margy.CARRIER] + "  ·  " + country[Margy.MCCMNC]
                + "  ·  " + country[Margy.ISO].toUpperCase(Locale.US)));
        row.addView(text, grow());

        if (selected) row.addView(new Check(this, Accent.colour()));

        row.setOnClickListener(v -> {
            Margy.setIso(country[Margy.ISO]);
            countriesOpen = false;
            markChanged();
        });
        return sized(row, 60);
    }

    /**
     * A country's flag, spelled rather than drawn.
     *
     * The two letters of a country's code have twin characters in the regional
     * indicator block, and a pair of those is a flag -- so `ru` becomes the
     * Russian flag with no picture involved, in whichever emoji font is on.
     * Which is also why it is worth having: with an emoji pack chosen in the
     * settings above, these are that pack's flags.
     */
    private View flag(String iso) {
        TextView view = new TextView(this);
        StringBuilder out = new StringBuilder();
        String code = iso.toUpperCase(Locale.US);
        for (int i = 0; i < code.length() && i < 2; i++) {
            int letter = code.charAt(i) - 'A';
            if (letter < 0 || letter > 25) return spacer();
            out.appendCodePoint(0x1F1E6 + letter);
        }
        view.setText(out.toString());
        // always Twemoji, whatever is chosen for the app: a phone's own flag
        // emoji is often two letters in a box, and this one is always a flag
        android.graphics.Typeface flags = Fonts.twemoji(this);
        if (flags != null) view.setTypeface(flags);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        view.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams size =
                new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT);
        size.rightMargin = dp(10);
        view.setLayoutParams(size);
        return view;
    }

    private View spacer() {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(34), 1));
        return view;
    }

    private View accentHead() {
        LinearLayout row = row();
        row.addView(icon("palette"));
        row.addView(label(Text.ACCENT_COLOUR), grow());
        row.addView(new Dot(this, Accent.colour(), false));

        TextView chevron = new TextView(this);
        chevron.setText(accentOpen ? "⌃" : "⌄");
        chevron.setTextColor(skin.muted());
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chevron.setPadding(dp(12), 0, 0, 0);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            accentOpen = !accentOpen;
            rebuild();
        });
        return sized(row, 56);
    }

    /** Counted in preferences: asked on the third visit and then never again. */
    private static final String VISITS = "settings_visits";
    private static final String ASKED = "settings_asked";
    private static final int ON_VISIT = 3;

    /**
     * Ask once, on the third time this screen is opened.
     *
     * Once, and only once: refusing is remembered for good, and so is having
     * been asked. Somebody who opens the settings twenty times should be left
     * alone after the first answer.
     */
    private void maybeAskForSupport() {
        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences(Margy.PREFS, MODE_PRIVATE);
            if (prefs.getBoolean(ASKED, false)) return;

            int visits = prefs.getInt(VISITS, 0) + 1;
            prefs.edit().putInt(VISITS, visits).apply();
            if (visits < ON_VISIT) return;
            prefs.edit().putBoolean(ASKED, true).apply();

            column.post(() -> Popup.ask(this, Text.REMIND_TITLE, Text.REMIND_TEXT,
                    Text.REMIND_MORE, this::showDonations,
                    Text.REMIND_NEVER, null,
                    null, null));
        } catch (Throwable error) {
            Diary.note("settings: " + error);
        }
    }

    // ------------------------------------------------------------ own badges

    private View mineHead() {
        LinearLayout row = row();
        row.addView(icon("favorite_border"));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(Text.MINE));
        text.addView(detail(Text.MINE_NOTE));
        row.addView(text, grow());

        TextView chevron = new TextView(this);
        chevron.setText(mineOpen ? "⌃" : "⌄");
        chevron.setTextColor(skin.muted());
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chevron.setPadding(dp(12), 0, 0, 0);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            mineOpen = !mineOpen;
            if (mineOpen) {
                ordering = null;
                Mine.ask(this::rebuild);
            }
            rebuild();
        });
        return sized(row, 64);
    }

    private View mineRows() {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);

        // the list the server last sent wins over whatever was being
        // rearranged: the first answer on a cold start is empty, and without
        // this that empty answer is what stayed on screen
        // a late answer from the server replaces the list, unless somebody is
        // in the middle of arranging it: it used to throw their order away
        if (Mine.tookFresh() && !rearranged) ordering = null;
        if (ordering == null) ordering = Mine.held();
        rows.addView(proveRow());
        if (ordering.isEmpty()) {
            rows.addView(quiet(Mine.everAsked() ? Text.MINE_NONE : Text.MINE_WAIT));
            return rows;
        }

        for (int i = 0; i < ordering.size(); i++) {
            final int at = i;
            final Mine.Held one = ordering.get(i);
            if (i > 0) rows.addView(line());

            LinearLayout row = row();
            row.setPadding(dp(16), 0, dp(16), 0);

            Badges.Badge badge = Badges.byId(one.id);
            row.addView(badgeDot(badge));
            // the short name, which is what a badge is called; the long line
            // is its description and belongs in the popup, not in a list
            row.addView(label(badge == null ? one.id : named(badge)), grow());

            // up and down rather than dragging: a row that can be dragged has
            // to fight the page it is on for the same gesture
            row.addView(mover("⌃", at > 0, () -> {
                java.util.Collections.swap(ordering, at, at - 1);
                rearranged = true;
                rebuild();
            }));
            row.addView(mover("⌄", at < ordering.size() - 1, () -> {
                java.util.Collections.swap(ordering, at, at + 1);
                rearranged = true;
                rebuild();
            }));

            M3Switch toggle = new M3Switch(this);
            toggle.colours(Accent.colour(), skin.muted(), skin.card);
            toggle.setChecked(one.shown, false);
            toggle.setOnClickListener(v -> {
                one.shown = !one.shown;
                rearranged = true;
                rebuild();
            });
            LinearLayout.LayoutParams size =
                    new LinearLayout.LayoutParams(dp(52), dp(32));
            size.leftMargin = dp(10);
            row.addView(toggle, size);

            rows.addView(sized(row, 60));
        }

        rows.addView(button(Text.MINE_SAVE, () -> Mine.save(ordering,
                (ok, trouble) -> {
                    if (needsProof(ok, trouble)) return;
                    Screen.say(ok ? Text.MINE_SAVED : Text.MINE_TOO_OFTEN);
                    if (ok) {
                        rearranged = false;
                        ordering = null;
                    }
                    rebuild();
                })));
        return rows;
    }

    /**
     * Whether this account has proved it is anybody's, and the way to do it.
     *
     * At the head of the badge list because nothing under it saves until this
     * is done, and because the reason is worth seeing once.
     */
    private View proveRow() {
        boolean done = Proof.proved();
        LinearLayout row = row();
        row.setPadding(dp(16), 0, dp(16), 0);
        row.addView(icon(done ? "verified_user" : "fingerprint"));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(done ? Text.PROVE_DONE : Text.PROVE));
        text.addView(detail(done ? Text.PROVE_DONE_NOTE : Text.PROVE_NOTE));
        row.addView(text, grow());

        if (!done) row.setOnClickListener(v -> proveAccount());
        return sized(row, 64);
    }

    /** Ask for a code, then show the card that takes the name. */
    private void proveAccount() {
        String already = Proof.waiting();
        if (already.length() > 0) {
            askToCheck(already);
            return;
        }
        Screen.say(Text.MINE_WAIT);
        Proof.want((ok, trouble) -> {
            if (!ok) {
                Screen.say(trouble);
                return;
            }
            askToCheck(Proof.waiting());
        });
    }

    /** A refusal that only means the account has not been proved yet. */
    private boolean needsProof(boolean ok, String trouble) {
        if (ok || !Mine.PROVE.equals(trouble)) return false;
        proveAccount();
        return true;
    }

    private void askToCheck(String code) {
        Popup.prove(this, code, (name, reply) -> Proof.check(name, (ok, trouble) -> {
            if (ok) {
                Screen.say(Text.PROVE_OK);
                ordering = null;
                Mine.ask(this::rebuild);
            }
            // the card stays open on a no, with the trouble under the code
            reply.said(ok, trouble, Proof.waiting(), Proof.wantsName());
            rebuild();
        }), reply -> Proof.fresh((ok, trouble) ->
                reply.said(ok, trouble, Proof.waiting(), false)));
    }

    /** Something to press, rather than a row that happens to do something. */
    private View button(String title, final Runnable action) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextColor(onAccent());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(13), 0, dp(13));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Accent.colour());
        pill.setCornerRadius(dp(14));
        view.setBackground(pill);
        view.setOnClickListener(v -> action.run());

        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(16), dp(12), dp(16), dp(16));
        holder.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return holder;
    }

    private View mover(String arrow, boolean can, final Runnable action) {
        TextView view = new TextView(this);
        view.setText(arrow);
        view.setTextColor(can ? skin.text : skin.muted());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), 0, dp(10), 0);
        if (can) view.setOnClickListener(v -> action.run());
        return view;
    }

    private View badgeDot(Badges.Badge badge) {
        android.widget.ImageView view = new android.widget.ImageView(this);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(26), dp(26));
        size.rightMargin = dp(12);
        view.setLayoutParams(size);
        if (badge == null) return view;
        android.graphics.Bitmap picture = badge.image.length() == 0
                ? null : Badges.picture(this, badge.image);
        if (picture != null) {
            view.setImageBitmap(picture);
        } else {
            // a badge that names no picture of its own is the mod's own note,
            // which is the same thing it is drawn as beside a name
            view.setImageBitmap(Badges.note());
        }
        if (badge.colour != 0) {
            view.setColorFilter(badge.colour, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        return view;
    }

    /** The one that is free until it is not. */
    private View freeBanner() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(Math.max(skin.radius, dp(18)));
        background.setColor(blend(0xFF40E0D0, skin.card, 0.82f));
        card.setBackground(background);

        TextView head = new TextView(this);
        head.setText(Text.FREE_BADGE);
        head.setTextColor(skin.text);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(head);

        TextView body = new TextView(this);
        body.setText(Text.FREE_BADGE_TEXT);
        body.setTextColor(skin.text);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setPadding(0, dp(4), 0, 0);
        card.addView(body);

        TextView take = new TextView(this);
        take.setText(Text.FREE_BADGE_TAKE);
        take.setTextColor(0xFF10221F);
        take.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        take.setTypeface(Typeface.DEFAULT_BOLD);
        take.setGravity(Gravity.CENTER);
        take.setPadding(0, dp(11), 0, dp(11));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xFF40E0D0);
        pill.setCornerRadius(dp(14));
        take.setBackground(pill);
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        below.topMargin = dp(14);
        take.setOnClickListener(v -> {
            if (!Mine.everAsked()) {
                Mine.ask(() -> Mine.takeFree(this::tookFree));
                return;
            }
            Mine.takeFree(this::tookFree);
        });
        card.addView(take, below);

        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(skin.margin, dp(14), skin.margin, dp(2));
        holder.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return holder;
    }

    private void tookFree(boolean ok, String trouble) {
        if (needsProof(ok, trouble)) return;
        Screen.say(ok ? Text.FREE_BADGE_GOT : Text.MINE_TOO_OFTEN);
        rebuild();
    }

    // ------------------------------------------------------ the donation

    /**
     * The first thing on the screen, and the only thing here that asks for
     * something. Short, because a long one is an advertisement.
     */
    private View donateBanner() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(Math.max(skin.radius, dp(18)));
        background.setColor(skin.card);
        card.setBackground(background);

        TextView head = new TextView(this);
        head.setText(Text.DONATE_BANNER);
        head.setTextColor(skin.text);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(head);

        TextView body = new TextView(this);
        body.setText(Text.DONATE_BANNER_TEXT);
        body.setTextColor(skin.text);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setPadding(0, dp(4), 0, 0);
        card.addView(body);

        TextView how = new TextView(this);
        how.setText(Text.DONATE_BANNER_HOW);
        how.setTextColor(skin.muted());
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        how.setPadding(0, dp(8), 0, 0);
        card.addView(how);

        TextView go = new TextView(this);
        go.setText(Text.DONATE_BANNER_BUTTON);
        go.setTextColor(onAccent());
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        go.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        go.setGravity(Gravity.CENTER);
        go.setPadding(0, dp(11), 0, dp(11));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Accent.colour());
        pill.setCornerRadius(dp(14));
        go.setBackground(pill);
        go.setOnClickListener(v -> showDonations());

        TextView write = new TextView(this);
        write.setText(Text.DONATE_BANNER_WRITE);
        write.setTextColor(skin.text);
        write.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        write.setGravity(Gravity.CENTER);
        write.setPadding(0, dp(11), 0, dp(11));
        GradientDrawable quiet = new GradientDrawable();
        quiet.setColor(0x00000000);
        quiet.setCornerRadius(dp(14));
        quiet.setStroke(dp(1), skin.muted());
        write.setBackground(quiet);
        write.setOnClickListener(v -> open(CHANNEL_WRITE));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        below.topMargin = dp(14);

        LinearLayout.LayoutParams half =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams second =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        second.leftMargin = dp(8);
        buttons.addView(go, half);
        buttons.addView(write, second);
        card.addView(buttons, below);

        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(skin.margin, dp(14), skin.margin, dp(2));
        holder.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return holder;
    }

    /** Open the thanks, then take the screen down to it. */
    private void showDonations() {
        thanksOpen = true;
        rebuild();
        final View anchor = donateAnchor;
        if (page == null || anchor == null) return;
        page.post(() -> {
            try {
                page.smoothScrollTo(0, anchor.getTop());
            } catch (Throwable ignored) {
            }
        });
    }

    /** Two colours mixed, for a banner that is the accent without shouting. */
    private static int blend(int colour, int into, float how) {
        int red = (int) (((colour >> 16) & 0xFF) * (1 - how) + ((into >> 16) & 0xFF) * how);
        int green = (int) (((colour >> 8) & 0xFF) * (1 - how) + ((into >> 8) & 0xFF) * how);
        int blue = (int) ((colour & 0xFF) * (1 - how) + (into & 0xFF) * how);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    // -------------------------------------------------------- the colours

    private View wallpaperRow() {
        final int colour = Accent.fromWallpaper();
        LinearLayout row = row();
        row.addView(icon("wallpaper"));
        row.addView(label(Text.ACCENT_WALLPAPER), grow());
        row.addView(new Dot(this, colour, colour == Accent.colour()));
        row.setOnClickListener(v -> {
            Accent.set(colour);
            markChanged();
        });
        return sized(row, 56);
    }

    /**
     * What opens, opens quickly.
     *
     * The screen is built again from nothing every time something is toggled,
     * so there is no view to animate from one height to another -- what there
     * is, is a view that was not there a moment ago. It fades in and rises a
     * few pixels, over a seventh of a second: long enough to read as opening,
     * short enough that nobody waits for it.
     */
    private View appear(View view) {
        if (view == null) return null;
        try {
            view.setAlpha(0f);
            view.setTranslationY(dp(-6));
            view.animate().alpha(1f).translationY(0f).setDuration(140).start();
        } catch (Throwable ignored) {
        }
        return view;
    }

    // ------------------------------------------------------ the texture packs

    private static final int PICK_TEXTURES = 0x4D54;  // "MT"

    private static final String TEXTURE_DOCS =
            "https://git.narez.xyz/narezany/MargyT/src/branch/main/docs/textures.md";

    private View packRow(final Textures.Pack one) {
        boolean chosen = one.file.equals(Textures.pack());
        LinearLayout row = row();
        row.addView(icon("image"));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(one.name));
        String under = one.author.length() > 0 ? one.author : one.about;
        if (!one.fits()) under = Text.TEXTURES_WRONG_VERSION + "  ·  " + one.tiktok;
        if (under.length() > 0) text.addView(detail(under));
        row.addView(text, grow());

        if (chosen) row.addView(new Check(this, Accent.colour()));
        row.setOnClickListener(v -> {
            Textures.choose(chosen ? "" : one.file);
            markChanged();
        });
        row.setOnLongClickListener(v -> {
            Textures.remove(this, one.file);
            markChanged();
            return true;
        });
        return sized(row, 64);
    }

    /** Whether an export is already running: two at once write one file. */
    private static final java.util.concurrent.atomic.AtomicBoolean exporting =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void exportTextures(final boolean withXml) {
        if (!exporting.compareAndSet(false, true)) {
            Screen.say(Text.TEXTURES_EXPORTING);
            return;
        }
        Screen.progress(Text.TEXTURES_EXPORTING, 0);
        Net.away("textures", () -> {
            final java.io.File out = Textures.export(this, withXml);
            exporting.set(false);
            runOnUiThread(() -> Screen.say(out == null
                    ? Text.TEXTURES_FAILED : Text.TEXTURES_EXPORTED));
        });
    }

    private void pickTextures() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_TEXTURES);
        } catch (Throwable error) {
            Toast.makeText(this, String.valueOf(error), Toast.LENGTH_LONG).show();
        }
    }

    /** A value between nothing and something, set by dragging. */
    private interface Chosen {
        void at(int value);
    }

    private View slider(String title, int now, int most, final Chosen chosen) {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(16), dp(12), dp(16), dp(14));
        rows.addView(label(title));

        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(most);
        bar.setProgress(Math.min(now, most));
        bar.getProgressDrawable().setColorFilter(
                Accent.colour(), android.graphics.PorterDuff.Mode.SRC_IN);
        bar.getThumb().setColorFilter(
                Accent.colour(), android.graphics.PorterDuff.Mode.SRC_IN);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seek, int value, boolean human) {
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seek) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seek) {
                chosen.at(seek.getProgress());
            }
        });
        rows.addView(bar);
        return rows;
    }

    /** How far the chosen background sits from the theme's own extreme. */
    private View strengthRow() {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(16), dp(12), dp(16), dp(14));

        rows.addView(label(Text.THEME_STRENGTH));

        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Themes.strength());
        bar.getProgressDrawable().setColorFilter(
                Accent.colour(), android.graphics.PorterDuff.Mode.SRC_IN);
        bar.getThumb().setColorFilter(
                Accent.colour(), android.graphics.PorterDuff.Mode.SRC_IN);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seek, int value, boolean human) {
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seek) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seek) {
                Themes.setStrength(seek.getProgress());
                markChanged();
            }
        });
        rows.addView(bar);
        rows.addView(quiet(Text.THEME_STRENGTH_NOTE));
        return rows;
    }

    // ---------------------------------------------------------- the font

    private static final int PICK_FONT = 0x4D46;  // "MF"
    private static final int PICK_EMOJI = 0x4D45;  // "ME"

    private View fontHead() {
        LinearLayout row = row();
        row.addView(icon("text_fields"));
        row.addView(label(Text.FONT), grow());

        TextView now = detail(fontName(Fonts.name()));
        now.setTextColor(skin.text);
        row.addView(now);

        TextView chevron = new TextView(this);
        chevron.setText(fontOpen ? "⌃" : "⌄");
        chevron.setTextColor(skin.muted());
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chevron.setPadding(dp(12), 0, 0, 0);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            fontOpen = !fontOpen;
            rebuild();
        });
        return sized(row, 56);
    }

    private static String named(Badges.Badge badge) {
        if (badge.title != null && badge.title.length() > 0) return badge.title;
        return badge.text;
    }

    private static final int PICK_BANNER = 0x4D42;  // "MB"

    private LinearLayout bannerCard() {
        LinearLayout card = card();
        if (!Mine.holds(Tester.SUPPORTER)) {
            card.addView(actionRow("wallpaper", Text.BANNER, null, () -> {}));
            card.addView(caption(Text.GRADIENT_ONLY));
            return card;
        }
        card.addView(actionRow("wallpaper", Text.BANNER_PICK, null, this::pickBanner));
        if (Looks.hasBanner(Account.id())) {
            card.addView(line());
            card.addView(actionRow("block", Text.BANNER_OFF, null, () -> Banner.drop(
                    (ok, trouble) -> {
                        if (needsProof(ok, trouble)) return;
                        Popup.show(this, Text.BANNER,
                                ok ? Text.GRADIENT_SAVED : trouble, Text.TEST_CLOSE);
                        rebuild();
                    })));
        }
        if (Looks.hasBanner(Account.id())) {
            card.addView(line());
            card.addView(slider(Text.BANNER_DIM, Banner.dim(), 90, value ->
                    Banner.setDim(value, (ok, trouble) -> {
                        if (needsProof(ok, trouble)) return;
                        if (!ok) Toast.makeText(this, trouble, Toast.LENGTH_LONG).show();
                        rebuild();
                    })));
            card.addView(quiet(Text.BANNER_DIM_NOTE));
        }
        card.addView(line());
        card.addView(slider(Text.BANNER_SHADE, Banner.shade(), 100, value -> {
            Banner.setShade(value);
            markChanged();
        }));
        card.addView(quiet(Text.BANNER_SHADE_NOTE));
        card.addView(caption(Text.BANNER_NOTE));
        card.addView(quiet(Text.BANNER_RULES));
        return card;
    }

    private void pickBanner() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_BANNER);
        } catch (Throwable error) {
            Toast.makeText(this, String.valueOf(error), Toast.LENGTH_LONG).show();
        }
    }

    // -------------------------------------------------------- the gradient

    private java.util.List<Integer> gradientColours;
    private int gradientSlot;

    private LinearLayout gradientCard() {
        LinearLayout card = card();
        if (!Mine.holds(Tester.SUPPORTER)) {
            card.addView(actionRow("gradient", Text.GRADIENT, null, () -> {}));
            card.addView(caption(Text.GRADIENT_ONLY));
            return card;
        }
        if (gradientColours == null) gradientColours = Gradient.starting();
        if (gradientSlot >= gradientColours.size()) gradientSlot = 0;

        card.addView(gradientPreview());
        card.addView(gradientDots());

        int[] hsv = Gradient.hsv(gradientColours.get(gradientSlot));
        card.addView(slider(Text.GRADIENT_HUE, hsv[0], 359, value -> setSlot(value, -1, -1)));
        card.addView(slider(Text.GRADIENT_SAT, hsv[1], 100, value -> setSlot(-1, value, -1)));
        card.addView(slider(Text.GRADIENT_VALUE, hsv[2], 100, value -> setSlot(-1, -1, value)));

        card.addView(line());
        if (gradientColours.size() < Gradient.MOST) {
            card.addView(actionRow("palette", Text.GRADIENT_ADD, null, () -> {
                gradientColours.add(gradientColours.get(gradientColours.size() - 1));
                gradientSlot = gradientColours.size() - 1;
                rebuild();
            }));
        }
        if (gradientColours.size() > Gradient.FEWEST) {
            card.addView(actionRow("block", Text.GRADIENT_DROP_ONE, null, () -> {
                gradientColours.remove(gradientSlot);
                gradientSlot = 0;
                rebuild();
            }));
        }
        card.addView(line());
        card.addView(actionRow("star", Text.GRADIENT_SAVE, null, () -> Gradient.save(
                gradientColours, (ok, trouble) -> {
                    if (needsProof(ok, trouble)) return;
                    Popup.show(this, Text.GRADIENT,
                            ok ? Text.GRADIENT_SAVED : trouble, Text.TEST_CLOSE);
                })));
        card.addView(actionRow("visibility_off", Text.GRADIENT_OFF, null, () -> Gradient.drop(
                (ok, trouble) -> {
                    if (needsProof(ok, trouble)) return;
                    Popup.show(this, Text.GRADIENT,
                            ok ? Text.GRADIENT_SAVED : trouble, Text.TEST_CLOSE);
                })));
        card.addView(caption(Text.GRADIENT_NOTE));
        return card;
    }

    private void setSlot(int hue, int saturation, int value) {
        int[] hsv = Gradient.hsv(gradientColours.get(gradientSlot));
        if (hue >= 0) hsv[0] = hue;
        if (saturation >= 0) hsv[1] = saturation;
        if (value >= 0) hsv[2] = value;
        gradientColours.set(gradientSlot, Gradient.from(hsv[0], hsv[1], hsv[2]));
        rebuild();
    }

    private View gradientPreview() {
        int[] colours = new int[gradientColours.size()];
        for (int i = 0; i < colours.length; i++) colours[i] = gradientColours.get(i);
        View bar = new View(this);
        android.graphics.drawable.GradientDrawable paint =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        colours);
        paint.setCornerRadius(dp(10));
        bar.setBackground(paint);
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(16), dp(14), dp(16), dp(6));
        holder.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        return holder;
    }

    private View gradientDots() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(dp(16), dp(6), dp(16), dp(10));
        for (int i = 0; i < gradientColours.size(); i++) {
            final int at = i;
            Dot dot = new Dot(this, gradientColours.get(i), i == gradientSlot);
            dot.setOnClickListener(v -> {
                gradientSlot = at;
                rebuild();
            });
            line.addView(dot, new LinearLayout.LayoutParams(dp(36), dp(36), 1f));
        }
        return line;
    }

    private static String fpsName(String which) {
        if (which == null || which.length() == 0) return Text.FPS_AUTO;
        return String.format(Text.FPS_LOCKED, which + " FPS");
    }

    private static String fontName(String which) {
        if (Fonts.SANS.equals(which)) return Text.FONT_SANS;
        if (Fonts.SANS_LIGHT.equals(which)) return Text.FONT_SANS_LIGHT;
        if (Fonts.SANS_CONDENSED.equals(which)) return Text.FONT_SANS_CONDENSED;
        if (Fonts.SERIF.equals(which)) return Text.FONT_SERIF;
        if (Fonts.MONOSPACE.equals(which)) return Text.FONT_MONOSPACE;
        if (Fonts.CURSIVE.equals(which)) return Text.FONT_CURSIVE;
        if (Fonts.FILE.equals(which)) return Text.FONT_FILE;
        return Text.FONT_SYSTEM;
    }

    private View fontChoices() {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);

        String now = Fonts.name();
        for (int i = 0; i < Fonts.PRESETS.length; i++) {
            final String which = Fonts.PRESETS[i];
            if (i > 0) rows.addView(line());
            rows.addView(pickRow(fontName(which), which.equals(now),
                    Fonts.SYSTEM.equals(which) ? null : which, () -> {
                        Fonts.choose(which);
                        markChanged();
                    }));
        }
        rows.addView(line());
        rows.addView(actionRow("download", Text.FONT_PICK,
                Fonts.FILE.equals(now) ? Text.FONT_FILE : null, this::pickFont));

        rows.addView(line());
        rows.addView(section(Text.EMOJI));
        String emoji = Fonts.emoji();
        for (int i = 0; i < Fonts.EMOJI_PACKS.length; i++) {
            final String which = Fonts.EMOJI_PACKS[i];
            if (i > 0) rows.addView(line());
            rows.addView(pickRow(emojiName(which), which.equals(emoji), null, () -> {
                Fonts.chooseEmoji(this, which);
                markChanged();
            }));
        }
        rows.addView(line());
        rows.addView(actionRow("download", Text.EMOJI_FILE,
                Fonts.EMOJI_FILE.equals(emoji) ? Text.EMOJI_FILE : null, this::pickEmoji));
        rows.addView(quiet(Text.EMOJI_NOTE));
        return rows;
    }

    private static String emojiName(String which) {
        if (Fonts.TWEMOJI.equals(which)) return Text.EMOJI_TWEMOJI;
        if (Fonts.NOTO.equals(which)) return Text.EMOJI_NOTO;
        if (Fonts.BLOBMOJI.equals(which)) return Text.EMOJI_BLOB;
        return Text.EMOJI_SYSTEM;
    }

    private void pickEmoji() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_EMOJI);
        } catch (Throwable error) {
            Toast.makeText(this, String.valueOf(error), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * A row that is one of several answers to one question.
     *
     * `preview` is a typeface name to write the row in, so a font can be read
     * before it is chosen; null leaves the row in the app's own.
     */
    private View pickRow(String title, boolean chosen, String preview, final Runnable action) {
        LinearLayout row = row();
        row.setPadding(dp(16), 0, dp(16), 0);

        TextView name = label(title);
        if (preview != null) {
            try {
                name.setTypeface(android.graphics.Typeface.create(
                        preview, android.graphics.Typeface.NORMAL));
            } catch (Throwable ignored) {
            }
        }
        row.addView(name, grow());
        if (chosen) row.addView(new Check(this, Accent.colour()));
        row.setOnClickListener(v -> action.run());
        return sized(row, 52);
    }

    private void pickFont() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, PICK_FONT);
        } catch (Throwable error) {
            Toast.makeText(this, String.valueOf(error), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------- the icon

    private View iconHead() {
        LinearLayout row = row();
        row.addView(icon("image"));
        row.addView(label(Text.ICON), grow());

        TextView now = detail(Launcher.nameOf(Launcher.chosen()));
        now.setTextColor(skin.text);
        row.addView(now);

        TextView chevron = new TextView(this);
        chevron.setText(iconOpen ? "⌃" : "⌄");
        chevron.setTextColor(skin.muted());
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chevron.setPadding(dp(12), 0, 0, 0);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            iconOpen = !iconOpen;
            rebuild();
        });
        return sized(row, 56);
    }

    private View iconChoices() {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);

        String now = Launcher.chosen();
        String[] all = Launcher.all();
        for (int i = 0; i < all.length; i++) {
            final String which = all[i];
            if (i > 0) rows.addView(line());
            LinearLayout row = row();
            row.setPadding(dp(16), 0, dp(16), 0);

            android.widget.ImageView shot = new android.widget.ImageView(this);
            android.graphics.Bitmap picture = Launcher.preview(which);
            if (picture != null) shot.setImageBitmap(picture);
            LinearLayout.LayoutParams size =
                    new LinearLayout.LayoutParams(dp(34), dp(34));
            size.rightMargin = dp(14);
            row.addView(shot, size);

            row.addView(label(Launcher.nameOf(which)), grow());
            if (which.equals(now)) row.addView(new Check(this, Accent.colour()));
            row.setOnClickListener(v -> {
                Launcher.choose(this, which);
                rebuild();
            });
            rows.addView(sized(row, 56));
        }
        rows.addView(line());
        rows.addView(quiet(Text.ICON_NOTE));
        rows.addView(linkRow("link", Text.ICON_CONTEST, "@margytiktok", CONTEST));
        return rows;
    }

    /** Where the icons come from. */
    private static final String CONTEST = "https://t.me/margytiktok/49";

    /** The row that opens one of the theme's two colours. */
    private View shadeHead(final boolean forText) {
        LinearLayout row = row();
        row.addView(icon(forText ? "text_fields" : "format_color_fill"));
        row.addView(label(forText ? Text.THEME_TEXT : Text.THEME_BACKGROUND), grow());
        row.addView(new Dot(this, forText ? Themes.text() : Themes.background(), false));

        boolean open = forText ? textOpen : backgroundOpen;
        TextView chevron = new TextView(this);
        chevron.setText(open ? "⌃" : "⌄");
        chevron.setTextColor(skin.muted());
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chevron.setPadding(dp(12), 0, 0, 0);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            if (forText) textOpen = !textOpen;
            else backgroundOpen = !backgroundOpen;
            rebuild();
        });
        return sized(row, 56);
    }

    /**
     * What a text colour and a background colour may be.
     *
     * Two ramps rather than one palette: what a background wants is a set of
     * near-blacks and near-whites, and what text wants is the other end. The
     * accent's own dots are bright colours and would be no use for either.
     */
    private static final int[] DARKS = {
            0xFF000000, 0xFF0B0B0F, 0xFF121212, 0xFF161823, 0xFF1B1B1B,
            0xFF0D1B2A, 0xFF12232E, 0xFF1A1423, 0xFF14261C, 0xFF241A1A,
    };

    private static final int[] LIGHTS = {
            0xFFFFFFFF, 0xFFF6F6F6, 0xFFEDEDED, 0xFFE8E4DA, 0xFFDCDCDC,
            0xFFCFD8DC, 0xFFB0B8C4, 0xFF8A8A8A, 0xFF5A5A5A, 0xFF2E2E2E,
    };

    private View shades(final boolean forText) {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(16), dp(12), dp(16), dp(16));

        int[] choices = forText ? LIGHTS : DARKS;
        int now = forText ? Themes.text() : Themes.background();
        LinearLayout line = null;
        for (int i = 0; i < choices.length; i++) {
            if (i % 5 == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setPadding(0, dp(6), 0, dp(6));
                rows.addView(line);
            }
            final int colour = choices[i];
            Dot dot = new Dot(this, colour, colour == now);
            dot.setOnClickListener(v -> {
                if (forText) Themes.setText(colour);
                else Themes.setBackground(colour);
                markChanged();
            });
            line.addView(dot, new LinearLayout.LayoutParams(dp(36), dp(36), 1f));
        }
        return rows;
    }

    private View palette() {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(16), dp(12), dp(16), dp(16));

        int[] palette = Accent.palette();
        LinearLayout line = null;
        for (int i = 0; i < palette.length; i++) {
            if (i % 5 == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setPadding(0, dp(6), 0, dp(6));
                rows.addView(line);
            }
            final int colour = palette[i];
            Dot dot = new Dot(this, colour, colour == Accent.colour());
            dot.setOnClickListener(v -> {
                Accent.set(colour);
                markChanged();
            });
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(dp(36), dp(36), 1f);
            line.addView(dot, params);
        }

        TextView note = new TextView(this);
        note.setText(Text.ACCENT_NOTE);
        note.setTextColor(skin.muted());
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(0, dp(10), 0, 0);
        rows.addView(note);
        return rows;
    }

    /**
     * A Material icon, tinted to whatever this screen turned out to be.
     *
     * Decoded once per name and kept: the settings screen is rebuilt on every
     * tap, and decoding a dozen pngs each time would be felt.
     */
    private View icon(String name) {
        ImageView view = new ImageView(this);
        Bitmap bitmap = ICONS.get(name);
        if (bitmap == null) {
            try {
                String data = Icons.PNG.get(name);
                if (data != null) {
                    byte[] png = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                    bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (bitmap != null) ICONS.put(name, bitmap);
                }
            } catch (Throwable ignored) {
            }
        }
        if (bitmap != null) {
            view.setImageBitmap(bitmap);
            view.setColorFilter(skin.muted(), android.graphics.PorterDuff.Mode.SRC_IN);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(22), dp(22));
        params.rightMargin = dp(14);
        view.setLayoutParams(params);
        return view;
    }

    private static final java.util.Map<String, Bitmap> ICONS =
            new java.util.HashMap<String, Bitmap>();

    /** What is set by a setting: one line and a switch, wherever it lives. */
    private interface Setting {
        void set(boolean on);
    }

    private View toggleRow(String picture, String title, boolean on, final Setting setting) {
        LinearLayout row = row();
        row.addView(icon(picture));
        row.addView(label(title), grow());

        final M3Switch toggle = new M3Switch(this);
        toggle.colours(Accent.colour(), skin.muted(), skin.card);
        toggle.setChecked(on);
        toggle.setOnChanged(checked -> {
            setting.set(checked);
            markChanged();
        });
        row.addView(toggle);

        row.setOnClickListener(v -> {
            toggle.setChecked(!toggle.isChecked(), true);
            setting.set(toggle.isChecked());
            markChanged();
        });
        return sized(row, 56);
    }

    /**
     * An identifier, and a tap to copy it.
     *
     * The long one does not fit on a phone, so what is shown is the ends of it
     * and what is copied is all of it.
     */
    /**
     * A switch with a word beside it saying not to trust it yet.
     *
     * The only thing in the mod that acts on its own and the only one that
     * sends anything, so it says so on the row rather than in a note nobody
     * reads.
     */
    private View betaRow(String picture, String title, boolean on, final Setting setting) {
        LinearLayout row = row();
        row.addView(icon(picture));
        row.addView(label(title));

        TextView beta = new TextView(this);
        beta.setText(Text.BETA);
        beta.setTextColor(onAccent());
        beta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        beta.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        beta.setPadding(dp(7), dp(2), dp(7), dp(3));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(Accent.colour());
        chip.setCornerRadius(dp(9));
        beta.setBackground(chip);
        LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        place.leftMargin = dp(8);
        row.addView(beta, place);

        row.addView(new View(this), grow());

        final M3Switch toggle = new M3Switch(this);
        toggle.colours(Accent.colour(), skin.muted(), skin.card);
        toggle.setChecked(on);
        toggle.setOnChanged(checked -> {
            setting.set(checked);
            markChanged();
        });
        row.addView(toggle);
        return sized(row, 56);
    }

    private View stickerHead() {
        LinearLayout row = row();
        row.addView(icon("star"));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(Text.STREAK_STICKER));
        int many = Streaks.offered().size();
        text.addView(detail(many == 0 ? Text.STREAK_NOTHING : String.valueOf(many)));
        row.addView(text, grow());

        TextView chevron = new TextView(this);
        chevron.setText(streakOpen ? "⌃" : "⌄");
        chevron.setTextColor(skin.muted());
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            streakOpen = !streakOpen;
            rebuild();
        });
        return sized(row, 64);
    }

    /** The stickers the app has drawn so far, as something to point at. */
    private View stickerChoices() {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(16), dp(10), dp(16), dp(14));

        java.util.List<String> ids = Streaks.offered();
        if (ids.isEmpty()) {
            rows.addView(quiet(Text.STREAK_NOTHING));
            return rows;
        }

        String chosen = Streaks.chosen();
        LinearLayout line = null;
        for (int i = 0; i < ids.size() && i < 24; i++) {
            if (i % 5 == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setPadding(0, dp(5), 0, dp(5));
                rows.addView(line);
            }
            final String id = ids.get(i);
            View one = stickerTile(id, id.equals(chosen));
            LinearLayout.LayoutParams size =
                    new LinearLayout.LayoutParams(dp(52), dp(52), 1f);
            line.addView(one, size);
        }
        return rows;
    }

    private View stickerTile(final String id, boolean chosen) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setPadding(dp(4), dp(4), dp(4), dp(4));
        if (chosen) {
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(0x00000000);
            ring.setStroke(dp(2), Accent.colour());
            ring.setCornerRadius(dp(10));
            view.setBackground(ring);
        }
        Bitmap picture = Streaks.thumbnail(this, id);
        if (picture != null) view.setImageBitmap(picture);
        view.setOnClickListener(v -> {
            Streaks.choose(id);
            rebuild();
        });
        return view;
    }

    /** A row that does something at once, rather than setting anything. */
    private View actionRow(String picture, String title, String detail, final Runnable action) {
        LinearLayout row = row();
        row.addView(icon(picture));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(title));
        if (detail != null) text.addView(detail(detail));
        row.addView(text, grow());

        row.setOnClickListener(v -> action.run());
        return sized(row, detail == null ? 56 : 64);
    }

    /**
     * What this is and what it was built from, at the very bottom.
     *
     * Small and grey on purpose: nobody needs it until something has gone
     * wrong, and then it is the first thing anybody will ask for.
     */
    private View versions() {
        TextView view = new TextView(this);
        // the author and the licence live here as well: the licence asks that
        // a fork keep this line and the card that asks for support, rather
        // than moving the name into print nobody reads
        view.setText("MargyT " + Version.MOD + "  ·  TikTok " + Version.TIKTOK
                + "\n" + Text.MADE_BY);
        view.setTextColor(skin.muted());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setGravity(Gravity.CENTER);
        view.setPadding(skin.margin, dp(24), skin.margin, dp(8));
        return view;
    }

    private View idRow(String title, final String value) {
        LinearLayout row = row();
        row.addView(icon("fingerprint"));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(title));

        TextView shown = detail(value == null ? Text.ACCOUNT_UNKNOWN : shorten(value));
        if (value != null) shown.setTextColor(skin.text);
        text.addView(shown);
        row.addView(text, grow());

        if (value != null) {
            row.addView(away());
            row.setOnClickListener(v -> copy(title, value));
        }
        return sized(row, 64);
    }

    private static String shorten(String value) {
        if (value.length() <= 26) return value;
        return value.substring(0, 14) + "…" + value.substring(value.length() - 8);
    }

    // ----------------------------------------------------------- the plugins

    private static final int PICK_PLUGIN = 0x4D50;  // "MP"

    private View installRow() {
        LinearLayout row = row();
        row.addView(icon("extension"));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(Text.PLUGIN_INSTALL));
        text.addView(detail(Text.PLUGIN_INSTALL_NOTE));
        row.addView(text, grow());

        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextColor(Accent.colour());
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        plus.setPadding(dp(12), 0, 0, 0);
        row.addView(plus);

        row.setOnClickListener(v -> pickPlugin());
        return sized(row, 64);
    }

    /**
     * One installed plugin: what it says about itself, and a switch.
     *
     * Not `sized()` like the other rows -- a description is as tall as it is,
     * and a plugin whose author wrote two sentences should not have the second
     * one clipped.
     */
    private View pluginRow(final Plugins.Info info) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        Bitmap icon = info.icon();
        if (icon != null) {
            ImageView view = new ImageView(this);
            view.setImageBitmap(icon);
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams size =
                    new LinearLayout.LayoutParams(dp(40), dp(40));
            size.rightMargin = dp(14);
            row.addView(view, size);
        } else {
            LinearLayout.LayoutParams size =
                    new LinearLayout.LayoutParams(dp(40), dp(40));
            size.rightMargin = dp(14);
            row.addView(new Dot(this, Accent.colour(), false), size);
        }

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(info.name + "  " + info.version));

        String by = info.author;
        if (!info.description.isEmpty()) by = by + "  ·  " + info.description;
        text.addView(detail(by));
        if (info.trouble != null) text.addView(detail(info.trouble));
        row.addView(text, grow());

        final M3Switch toggle = new M3Switch(this);
        toggle.colours(Accent.colour(), skin.muted(), skin.card);
        toggle.setChecked(Plugins.isEnabled(info.id));
        toggle.setEnabled(info.trouble == null || Plugins.isEnabled(info.id));
        toggle.setOnChanged(checked -> {
            Plugins.setEnabled(info.id, checked);
            markChanged();
        });
        row.addView(toggle);

        row.setOnLongClickListener(v -> {
            askToRemove(info);
            return true;
        });
        return row;
    }

    private void pickPlugin() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // .mtp is nobody's registered type, so the picker is shown everything
            intent.setType("*/*");
            startActivityForResult(intent, PICK_PLUGIN);
        } catch (Throwable error) {
            Toast.makeText(this, String.valueOf(error), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        Uri source = data.getData();
        if (source == null) return;

        if (request == PICK_TEXTURES) {
            if (Textures.install(this, source)) markChanged();
            else Toast.makeText(this, Text.TEXTURES_FAILED, Toast.LENGTH_LONG).show();
            rebuild();
            return;
        }
        if (request == PICK_BANNER) {
            Banner.send(this, source, (ok, trouble) -> {
                if (needsProof(ok, trouble)) return;
                Popup.show(this, Text.BANNER, ok ? Text.GRADIENT_SAVED : trouble,
                        Text.TEST_CLOSE);
                rebuild();
            });
            return;
        }
        if (request == PICK_FONT || request == PICK_EMOJI) {
            if (Fonts.take(this, source, request == PICK_EMOJI)) markChanged();
            else Toast.makeText(this, Text.FONT_FAILED, Toast.LENGTH_LONG).show();
            rebuild();
            return;
        }
        if (request != PICK_PLUGIN) return;
        try {
            Plugins.install(this, source);
            Toast.makeText(this, Text.PLUGIN_INSTALLED, Toast.LENGTH_SHORT).show();
            markChanged();
        } catch (Throwable error) {
            Toast.makeText(this, String.valueOf(error.getMessage() == null
                    ? error : error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void askToRemove(final Plugins.Info info) {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(Text.PLUGIN_REMOVE_ASK)
                    .setMessage(info.name + "  " + info.version)
                    .setNegativeButton(Text.CANCEL, null)
                    .setPositiveButton(Text.PLUGIN_REMOVE, (dialog, which) -> {
                        Plugins.uninstall(this, info.id);
                        markChanged();
                    })
                    .show();
        } catch (Throwable error) {
            Toast.makeText(this, String.valueOf(error), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------- the links

    private View linkRow(String picture, String title, String handle, final String url) {
        LinearLayout row = row();
        row.addView(icon(picture));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(title));
        text.addView(detail(handle));
        row.addView(text, grow());
        row.addView(away());

        row.setOnClickListener(v -> open(url));
        return sized(row, 64);
    }

    private View thanksHead() {
        LinearLayout row = row();
        row.addView(icon("favorite_border"));
        row.addView(label(Text.THANKS), grow());

        TextView chevron = new TextView(this);
        chevron.setText(thanksOpen ? "⌃" : "⌄");
        chevron.setTextColor(skin.muted());
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            thanksOpen = !thanksOpen;
            rebuild();
        });
        return sized(row, 56);
    }

    /** Who made this, and the two ways to pay for it. */
    private View thanks() {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(6), 0, dp(10));

        rows.addView(quiet(Text.THANKS_NOTE));
        rows.addView(owner());
        rows.addView(person("Claude Opus 5", Text.THANKS_CLAUDE, null));
        rows.addView(person("апрель14", Text.THANKS_HELPER, HELPER));

        rows.addView(line());

        TextView heading = new TextView(this);
        heading.setText(Text.DONATE);
        heading.setTextColor(skin.text);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(dp(16), dp(14), dp(16), dp(2));
        rows.addView(heading);

        rows.addView(quiet(Text.DONATE_NOTE));

        LinearLayout card = row();
        LinearLayout cardText = new LinearLayout(this);
        cardText.setOrientation(LinearLayout.VERTICAL);
        cardText.addView(label(spaced(CARD_NUMBER)));
        cardText.addView(detail(Text.CARD + "  ·  " + Text.TAP_TO_COPY));
        card.addView(cardText, grow());
        card.setOnClickListener(v -> copy(Text.CARD, CARD_NUMBER));
        rows.addView(sized(card, 64));

        rows.addView(linkRow("star", Text.YOOMONEY, Text.YOOMONEY_NOTE, YOOMONEY));
        return rows;
    }

    /** The one row with two places to go, so it asks which. */
    private View owner() {
        LinearLayout row = row();

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label("@narezany"));
        text.addView(detail(Text.THANKS_OWNER));
        row.addView(text, grow());
        row.addView(away());

        row.setOnClickListener(v -> {
            try {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("@narezany")
                        .setItems(new CharSequence[]{"Telegram", "TikTok"}, (dialog, which) ->
                                open(which == 0 ? OWNER_TELEGRAM : OWNER_TIKTOK))
                        .setNegativeButton(Text.CANCEL, null)
                        .show();
            } catch (Throwable error) {
                open(OWNER_TELEGRAM);
            }
        });
        return sized(row, 60);
    }

    private View person(String name, String what, final String url) {
        LinearLayout row = row();

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(name));
        text.addView(detail(what));
        row.addView(text, grow());

        if (url != null) {
            row.addView(away());
            row.setOnClickListener(v -> open(url));
        }
        return sized(row, 60);
    }

    /** The mark on a row that leaves the app. */
    private TextView away() {
        TextView arrow = new TextView(this);
        arrow.setText("↗");
        arrow.setTextColor(skin.muted());
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        arrow.setPadding(dp(12), 0, 0, 0);
        return arrow;
    }

    /** A paragraph that is there to be read once and then ignored. */
    private TextView quiet(String message) {
        TextView view = new TextView(this);
        view.setText(message);
        view.setTextColor(skin.muted());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setPadding(dp(16), dp(2), dp(16), dp(8));
        return view;
    }

    /** A card number is read off the screen by a person, so it is grouped. */
    private static String spaced(String digits) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 4 == 0) out.append(' ');
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    private void open(String url) {
        // a tiktok.com link belongs to the app this is running inside, so it is
        // offered there first: without this the browser opens, recognises the
        // link and hands it straight back, which is two screens for nothing
        url = inApp(url);
        if (url.contains("tiktok.com") && openWith(url, getPackageName())) return;
        if (openWith(url, null)) return;
        Toast.makeText(this, Text.NO_BROWSER, Toast.LENGTH_SHORT).show();
    }

    /**
     * The spelling of a link the app answers to.
     *
     * TikTok claims `www.tiktok.com` and a dozen others in its manifest, and
     * does not claim the bare domain -- so a link written without the `www`
     * resolves to nothing in the app, falls through to the browser, and the
     * browser hands it straight back. One prefix is the whole difference.
     */
    private static String inApp(String url) {
        if (url.startsWith("https://tiktok.com/")) {
            return "https://www.tiktok.com/" + url.substring("https://tiktok.com/".length());
        }
        return url;
    }

    private boolean openWith(String url, String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (packageName != null) intent.setPackage(packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void copy(String what, String text) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(what, text));
            Toast.makeText(this, Text.COPIED, Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Toast.makeText(this, String.valueOf(error), Toast.LENGTH_LONG).show();
        }
    }

    private View diaryHead() {
        LinearLayout row = row();
        row.addView(icon("article"));
        row.addView(label(Text.DIARY_TITLE), grow());

        TextView copy = new TextView(this);
        copy.setText(Text.COPY);
        copy.setTextColor(Accent.colour());
        copy.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        copy.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        copy.setPadding(dp(12), dp(8), dp(4), dp(8));
        copy.setOnClickListener(v -> copyDiary());
        row.addView(copy);

        TextView clear = new TextView(this);
        clear.setText(Text.CLEAR);
        clear.setTextColor(Accent.colour());
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clear.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clear.setPadding(dp(12), dp(8), dp(4), dp(8));
        clear.setOnClickListener(v -> {
            Diary.clear();
            Toast.makeText(this, Text.CLEARED, Toast.LENGTH_SHORT).show();
            rebuild();
        });
        row.addView(clear);

        TextView chevron = new TextView(this);
        chevron.setText(diaryOpen ? "⌃" : "⌄");
        chevron.setTextColor(skin.muted());
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        chevron.setPadding(dp(12), 0, 0, 0);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            diaryOpen = !diaryOpen;
            rebuild();
        });
        return sized(row, 56);
    }

    private View diaryLines() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(12));
        for (String entry : Diary.lines()) {
            TextView view = new TextView(this);
            view.setText(entry);
            view.setTextColor(skin.muted());
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            view.setPadding(0, dp(2), 0, 0);
            box.addView(view);
        }
        return box;
    }

    /**
     * Start TikTok over, so everything is drawn again in the new colour.
     *
     * The launcher's own intent, then out: what comes back is a fresh process
     * with nothing of the old one's colours cached in it.
     */
    private void restartTikTok() {
        try {
            android.content.Intent intent = getPackageManager()
                    .getLaunchIntentForPackage(getPackageName());
            if (intent == null) return;
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
            Runtime.getRuntime().exit(0);
        } catch (Throwable error) {
            Toast.makeText(this, String.valueOf(error), Toast.LENGTH_LONG).show();
        }
    }

    private void copyDiary() {
        try {
            StringBuilder out = new StringBuilder("MargyT\n");
            List<String> lines = Diary.lines();
            for (String line : lines) out.append(line).append('\n');
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("MargyT", out.toString()));
            Toast.makeText(this, Text.COPIED, Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Toast.makeText(this, String.valueOf(error), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------- the parts

    private View backArrow() {
        TextView arrow = new TextView(this);
        arrow.setText("←");
        arrow.setTextColor(skin.text);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        arrow.setPadding(skin.margin, dp(12), skin.margin, dp(12));
        arrow.setOnClickListener(v -> finish());
        return arrow;
    }

    private View title(String text) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(skin.text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(skin.margin, dp(8), skin.margin, dp(20));
        // five taps in a row and a cat turns up; one by accident does nothing
        view.setOnClickListener(v -> Cats.tapped(view));
        return view;
    }

    private View section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(skin.muted());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(skin.margin + dp(4), dp(16), skin.margin, dp(8));
        return view;
    }

    private View caption(String message) {
        TextView view = new TextView(this);
        view.setText(message);
        view.setTextColor(skin.muted());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setPadding(skin.margin + dp(4), dp(16), skin.margin + dp(4), dp(4));
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(skin.card);
        background.setCornerRadius(skin.radius);
        card.setBackground(background);
        card.setPadding(0, dp(4), 0, dp(4));
        return card;
    }

    private View wrap(View card) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(skin.margin, 0, skin.margin, 0);
        box.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View line() {
        View line = new View(this);
        line.setBackgroundColor((skin.text & 0x00FFFFFF) | 0x14000000);
        return sized(line, 1);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        return row;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(skin.text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return view;
    }

    private TextView detail(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(skin.muted());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return view;
    }

    private LinearLayout.LayoutParams grow() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View sized(View view, int height) {
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(height)));
        return view;
    }

    /**
     * The bar itself: one line of why, and the button that does it.
     *
     * Painted in the card colour with a hairline above, so it reads as resting
     * on the page rather than floating over it, and padded underneath by
     * whatever the navigation bar takes -- otherwise the button sits under the
     * gesture pill.
     */
    private View restartBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(skin.card);
        bar.addView(line());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(skin.margin, dp(12), skin.margin, dp(12) + navigationBar());

        TextView why = new TextView(this);
        why.setText(Text.RESTART_PENDING);
        why.setTextColor(skin.text);
        why.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        content.addView(why, grow());

        TextView button = new TextView(this);
        button.setText(Text.RESTART);
        button.setTextColor(onAccent());
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(18), dp(9), dp(18), dp(9));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Accent.colour());
        pill.setCornerRadius(dp(20));
        button.setBackground(pill);
        button.setOnClickListener(v -> restartTikTok());
        content.addView(button);

        bar.addView(content);
        return bar;
    }

    /**
     * What to write on the accent: the palette holds a mint and a near-white
     * as well as the pink, and white letters on either of those are unreadable.
     */
    private int onAccent() {
        int colour = Accent.colour();
        int red = (colour >> 16) & 0xFF, green = (colour >> 8) & 0xFF, blue = colour & 0xFF;
        int brightness = (red * 299 + green * 587 + blue * 114) / 1000;
        return brightness > 150 ? 0xFF1C2C24 : 0xFFFFFFFF;
    }

    /**
     * How much of the bottom belongs to the system, keyboard excluded.
     *
     * The system window inset counts the keyboard as well, so a rebuild while
     * one was up -- adding a hashtag, say -- padded the restart bar by the
     * height of the keyboard and turned it into half a screen.
     */
    private int navigationBar() {
        try {
            android.view.WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets == null) return 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                return insets.getInsets(
                        android.view.WindowInsets.Type.navigationBars()).bottom;
            }
            return insets.getStableInsetBottom();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private int statusBar() {
        try {
            android.view.WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            int top = 0;
            if (insets != null) {
                top = android.os.Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(
                                android.view.WindowInsets.Type.statusBars()).top
                        : insets.getStableInsetTop();
            }
            if (top > 0) return top;
        } catch (Throwable ignored) {
        }
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ----------------------------------------------------- the small shapes

    /** A tick, drawn rather than typed: the glyph fonts have is never the one. */
    private static final class Check extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Check(Context context, int colour) {
            super(context);
            paint.setColor(colour);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int size = Math.round(22 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float unit = getWidth() / 22f;
            paint.setStrokeWidth(unit * 2.2f);
            float y = getHeight() / 2f;
            canvas.drawLine(unit * 4, y + unit, unit * 9, y + unit * 5.5f, paint);
            canvas.drawLine(unit * 9, y + unit * 5.5f, unit * 18, y - unit * 5f, paint);
        }
    }

    /** One colour of the palette, and a ring around the one in use. */
    private static final class Dot extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean chosen;

        Dot(Context context, int colour, boolean chosen) {
            super(context);
            this.chosen = chosen;
            fill.setColor(colour);
            ring.setColor(colour);
            ring.setStyle(Paint.Style.STROKE);
            // clickable comes with the listener where there is one. Setting it
            // here made every swatch swallow a tap, including the ones in a
            // heading that are only there to be looked at
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int size = Math.round(26 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float centreX = getWidth() / 2f, centreY = getHeight() / 2f;
            float radius = Math.min(centreX, centreY) - (chosen ? 5 * density : 0);
            canvas.drawCircle(centreX, centreY, radius, fill);
            if (chosen) {
                ring.setStrokeWidth(2 * density);
                canvas.drawCircle(centreX, centreY, radius + 3.5f * density, ring);
            }
        }
    }
}
