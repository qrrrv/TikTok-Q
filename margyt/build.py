"""The build, start to finish.

    apk in -> manifest, icon, call sites, one more dex -> apk out

Nothing is decoded that does not have to be. The zip is copied entry by entry
with its compression intact, the resource table is patched where it lies, and
of fifty-two dex files only the ones that call telephony are taken apart.
"""

from __future__ import annotations

import base64
import os
import time
from typing import Dict, List, Optional

from . import accent as accent_module, artwork, dexpatch, icon as icon_module, manifest as manifest_module, nightly, resadd
from .apkzip import Apk, STORED
from .arsc import Arsc
from . import axml as axml_module
from .axml import Axml
from .dexpatch import Smali
from .toolchain import Toolchain

PACKAGE = "cat.narezany.margyt"
SETTINGS_ACTIVITY = PACKAGE + ".SettingsActivity"
STORE_ACTIVITY = PACKAGE + ".StoreActivity"
STARTUP_PROVIDER = PACKAGE + ".MargyProvider"

# The screen the MargyT row is put at the top of. The row itself is added to
# the view tree while the screen is drawn -- nothing patches the code that
# builds the list -- but the screen has to still be called this for the mod to
# recognise it, so the build checks rather than hopes.
TIKTOK_SETTINGS = "com.ss.android.ugc.aweme.setting.ui.SettingContainerActivity"
LABEL = "TikTok Q"

# The screen has no launcher entry of its own -- it is opened from the row in
# TikTok's settings -- but an activity still wants a label: it is what the
# recents card and any "open with" list show.
SETTINGS_LABEL = "TikTok Q settings"
SETTINGS_THEME = "Theme_DeviceDefault_Light_NoActionBar"

# what a renamed provider authority ends in, so two mods of the same app can
# sit on one phone without the installer refusing the second
AUTHORITY_MARKER = ".tiktokq"

# Without this an apk cannot hand Android another apk to install, and TikTok
# does not ask for it: its own updates come from a store. The mod's do not.
INSTALL_PERMISSION = "android.permission.REQUEST_INSTALL_PACKAGES"


def _constant(label: str) -> str:
    """A label as a Java constant name: "comment sticker tapped" -> COMMENT_..."""
    return "".join(c if c.isalnum() else "_" for c in label).upper()


def _java_name(owner: str) -> str:
    """A descriptor as a class name Class.forName understands."""
    return owner[1:-1].replace("/", ".")


class Build:
    def __init__(self, apk_path: str, out_path: str, root: str, tools: Toolchain,
                 workspace: str, keystore: Optional[str] = None,
                 accent: Optional[int] = None, test: bool = False):
        self.apk_path = apk_path
        self.out_path = out_path
        self.root = root
        self.tools = tools
        self.workspace = workspace
        self.keystore = keystore
        self.accent = dexpatch.TIKTOK_PINK if accent is None else accent
        self.test = test
        self.started = time.time()

    def say(self, message: str) -> None:
        print("\033[1;36m==>\033[0m %s" % message)

    def detail(self, message: str) -> None:
        print("    " + message)

    # ------------------------------------------------------------------ run

    def run(self) -> str:
        os.makedirs(self.workspace, exist_ok=True)
        os.makedirs(os.path.dirname(os.path.abspath(self.out_path)), exist_ok=True)

        self.say("Opening the apk")
        apk = Apk(self.apk_path)
        self.detail("%d entries" % len(apk.entries))

        manifest = Axml.parse(apk.read("AndroidManifest.xml"))
        package = manifest_module.package_name(manifest)
        self.detail("package %s, staying as it is" % package)
        self.detail("application class %s" % manifest_module.application_class(manifest))

        # everything built here has to be loadable as far back as the apk goes,
        # and the apk itself is the only honest source for how far back that is
        api = manifest_module.min_sdk(manifest)
        dex_format = dexpatch.dex_format(apk.read("classes.dex"))
        self.detail("minSdk %d, dex %s" % (api, dex_format))

        arsc = Arsc(apk.read("resources.arsc"))

        self.say("The accent colour")
        self.detail("#%06X" % (self.accent & 0xFFFFFF))
        moved: Dict[int, int] = {}
        for line in accent_module.bake(apk, arsc, dexpatch.TIKTOK_PINK, self.accent, moved):
            self.detail(line)

        # the mod is told what was moved and where from, so it can send those
        # shades on to whatever colour is chosen while the app runs
        self.write_baked_colour(moved)
        self.write_emblem()
        self.write_icons()
        self.write_version(manifest_module.version_name(manifest), self.test)
        self.write_patch_key()
        self.find_anchors(apk)
        self.write_theme(arsc)
        self.write_shots(manifest)

        self.say("Building the mod's own dex")
        dex_path = self.tools.compile_dex(
            os.path.join(self.root, "inject", "java"), self.workspace, api,
            os.path.join(self.root, "inject", "stubs"),
        )
        injected = open(dex_path, "rb").read()
        if dexpatch.dex_format(injected) != dex_format:
            raise RuntimeError(
                "the mod compiled to dex %s and the apk is dex %s -- an Android "
                "on the apk's minSdk (%d) would refuse to load it"
                % (dexpatch.dex_format(injected), dex_format, api)
            )
        missing = dexpatch.missing_targets(injected)
        if missing:
            raise RuntimeError(
                "the rewrites would land on %d method(s) the mod does not define:\n    %s\n"
                "smali assembles a call to a method that does not exist without "
                "complaining -- it would be a NoSuchMethodError on whichever "
                "screen reaches that call site first"
                % (len(missing), "\n    ".join(missing))
            )
        self.detail("%d bytes, dex %s, every rewrite has somewhere to land"
                    % (len(injected), dexpatch.dex_format(injected)))

        self.say("Name and icon")
        theme = self.tools.framework_constant(SETTINGS_THEME)
        for where in manifest_module.set_label(manifest, LABEL):
            self.detail("label on %s" % where)
        self.tools.check_attribute_ids(axml_module.ATTR_IDS)
        manifest_module.add_activity(manifest, SETTINGS_ACTIVITY, SETTINGS_LABEL, theme)
        self.detail("%s declared, reached from TikTok's own settings" % SETTINGS_ACTIVITY)
        manifest_module.add_activity(manifest, STORE_ACTIVITY, SETTINGS_LABEL, theme)
        self.detail("%s declared, the plugin store" % STORE_ACTIVITY)

        if not manifest_module.has_activity(manifest, TIKTOK_SETTINGS):
            raise RuntimeError(
                "%s is not in this apk -- TikTok's settings screen has been "
                "renamed, and the MargyT row would never appear in it"
                % TIKTOK_SETTINGS
            )
        if manifest_module.add_permission(manifest, INSTALL_PERMISSION):
            self.detail("asking for %s: the mod installs its own updates"
                        % INSTALL_PERMISSION.rsplit(".", 1)[-1])

        manifest_module.add_provider(
            manifest, STARTUP_PROVIDER, "%s.margyt" % package)
        self.detail("%s declared: the mod starts with the app" % STARTUP_PROVIDER)
        self.detail("the MargyT row goes on top of %s" % TIKTOK_SETTINGS.rsplit(".", 1)[-1])

        self.say("Provider authorities")
        shared = manifest_module.shared_authorities(manifest, package)
        renames = {old: old + AUTHORITY_MARKER for old in shared}
        if renames:
            for new in manifest_module.rename_authorities(manifest, renames):
                self.detail(new)
            self.detail("%d the package name does not cover, now ours alone"
                        % len(renames))
        else:
            self.detail("every authority is spelled with the package name, nothing to do")

        master = open(os.path.join(self.root, artwork.MASTER_PNG), "rb").read()
        for line in icon_module.replace_everywhere(apk, arsc, manifest, master):
            self.detail(line.strip())

        self.say("Icons to choose from")
        extended = self.add_icon_choices(apk, arsc, manifest)
        self.add_assets(apk)

        apk.replace("AndroidManifest.xml", manifest.build())
        if extended is not None:
            apk.replace("resources.arsc", extended, STORED)
        elif arsc.dirty:
            apk.replace("resources.arsc", arsc.build(), STORED)

        self.say("Rewriting the bytecode")
        self.patch_dex_files(apk, api, renames)

        name = dexpatch.next_dex_name(apk.names())
        apk.add(name, injected)
        self.detail("the mod's classes go in as %s" % name)

        self.say("Writing the apk")
        for gone in apk.drop_signature():
            self.detail("dropped %s" % gone)
        apk.write(self.out_path)
        apk.close()
        self.detail("%.0f MB" % (os.path.getsize(self.out_path) / 1e6))

        self.say("Signing")
        self.tools.sign(self.out_path, self.keystore)

        self.say("Done in %.0f s: %s" % (time.time() - self.started, self.out_path))
        return self.out_path

    def write_baked_colour(self, moved: Dict[int, int]) -> None:
        """Tell the mod what this apk was built with, and what was moved.

        Two things the app cannot work out for itself. The first is the accent
        the build baked, which is what its own screen is painted with before
        anyone chooses otherwise.

        The second is the list of shades that were moved and where each came
        from. TikTok's pink family is recognised at runtime by its hue, but the
        colours this build wrote in its place are a different family entirely --
        and one that sits near colours which are nobody's accent, like the green
        of somebody being online. So they are not recognised by hue at all:
        they are listed, exactly, with the shade each was made from. A colour
        the app hands over is looked up in that list, and what the mod sends on
        is wherever its original would go now.
        """
        # java reads these as signed ints, and a colour with full alpha is a
        # negative one there: sorted any other way, the binary search that
        # looks them up would walk off in the wrong direction
        keys = sorted(moved, key=_signed)
        path = os.path.join(self.root, "inject", "java", "cat", "narezany", "margyt",
                            "Baked.java")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(
                "package cat.narezany.margyt;\n\n"
                "/**\n"
                " * Written by the build. Do not edit: every build overwrites it.\n"
                " *\n"
                " * ACCENT is the colour baked into this apk. BAKED and FROM are the\n"
                " * shades the build wrote into the resources and the shade each one was\n"
                " * made from -- BAKED is sorted, so a lookup is a binary search, and the\n"
                " * answer is the entry of FROM beside it.\n"
                " */\n"
                "final class Baked {\n\n"
                "    private Baked() {}\n\n"
                "    static final int ACCENT = 0x%08X;\n\n"
                "%s"
                "}\n" % (self.accent, _tables(keys, moved))
            )
        if keys:
            self.detail("%d shades listed for the mod to recognise" % len(keys))

    def write_emblem(self) -> None:
        """Put the badge's picture into the code.

        This build adds no resources -- rewriting a 25 MB resource table is the
        one thing it refuses to do -- so the emblem travels as bytes in a class
        instead, and is decoded once when it is first drawn.
        """
        source = os.path.join(self.root, "icon_out", "mipmap-xxhdpi",
                              "ic_launcher_foreground.png")
        with open(source, "rb") as handle:
            png = base64.b64encode(handle.read()).decode("ascii")
        chunks = [png[at:at + 76] for at in range(0, len(png), 76)]
        body = "\n                    + ".join('"%s"' % chunk for chunk in chunks)

        path = os.path.join(self.root, "inject", "java", "cat", "narezany", "margyt",
                            "Emblem.java")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(
                "package cat.narezany.margyt;\n\n"
                "/**\n"
                " * Written by the build from icon_out/. Do not edit.\n"
                " *\n"
                " * The badge, as a png in base64. It is here rather than in res/ because\n"
                " * this build adds no resources to somebody else's apk.\n"
                " */\n"
                "final class Emblem {\n\n"
                "    private Emblem() {}\n\n"
                "    static final String PNG =\n            %s;\n"
                "}\n" % body
            )
        self.detail("emblem: %d bytes of png in the code" % os.path.getsize(source))

    def write_icons(self) -> None:
        """Put the settings screen's icons into the code.

        The same reason as the emblem: no resources are added to the apk, so a
        picture travels as bytes. They are Google's Material icons, Apache 2.0,
        black on transparent -- the mod tints them to whatever the screen it is
        drawing on turned out to be.
        """
        folder = os.path.join(self.root, "icons")
        names = sorted(f[:-4] for f in os.listdir(folder) if f.endswith(".png"))
        entries = []
        for name in names:
            with open(os.path.join(folder, name + ".png"), "rb") as handle:
                data = base64.b64encode(handle.read()).decode("ascii")
            chunks = [data[at:at + 72] for at in range(0, len(data), 72)]
            body = "\n                + ".join('"%s"' % chunk for chunk in chunks)
            entries.append('        PNG.put("%s",\n                %s);' % (name, body))

        path = os.path.join(self.root, "inject", "java", "cat", "narezany", "margyt",
                            "Icons.java")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(
                "package cat.narezany.margyt;\n\n"
                "import java.util.HashMap;\n"
                "import java.util.Map;\n\n"
                "/**\n"
                " * Written by the build from icons/. Do not edit.\n"
                " *\n"
                " * Google's Material icons, Apache 2.0, as base64 png. They are here\n"
                " * rather than in res/ because this build adds no resources to somebody\n"
                " * else's apk.\n"
                " */\n"
                "final class Icons {\n\n"
                "    private Icons() {}\n\n"
                "    static final Map<String, String> PNG = new HashMap<String, String>();\n\n"
                "    static {\n%s\n    }\n"
                "}\n" % "\n".join(entries)
            )
        self.detail("%d icons in the code" % len(names))

    def write_version(self, tiktok: str, test: bool = False) -> None:
        """Both versions, so the app can say what it is and what it patched."""
        path = os.path.join(self.root, "VERSION")
        with open(path, encoding="utf-8") as handle:
            mod = handle.read().strip()

        out = os.path.join(self.root, "inject", "java", "cat", "narezany", "margyt",
                           "Version.java")
        with open(out, "w", encoding="utf-8") as handle:
            handle.write(
                "package cat.narezany.margyt;\n\n"
                "/**\n"
                " * Written by the build. Do not edit.\n"
                " *\n"
                " * MOD is this repository's VERSION file; TIKTOK is what the apk this\n"
                " * was built from calls itself. The first is compared against the\n"
                " * repository to know whether there is an update; the second is there\n"
                " * so a person reporting something can say which TikTok it happened on.\n"
                " *\n"
                " * TEST marks a diagnostic build: it carries the current account id\n"
                " * faintly on screen. Local mod settings are available in both test\n"
                " * and release builds; server-backed claims keep their own validation.\n"
                " */\n"
                "final class Version {\n\n"
                "    private Version() {}\n\n"
                "    static final String MOD = \"%s\";\n"
                "    static final String TIKTOK = \"%s\";\n"
                "    static final boolean TEST = %s;\n"
                "}\n" % (mod, tiktok, "true" if test else "false")
            )
        self.detail("MargyT %s on TikTok %s%s"
                    % (mod, tiktok, ", a test build" if test else ""))

    #: where the key that signs patches is looked for. The private half is
    #: never in this repository and never on the server; the public half is
    #: built into the apk so a phone can tell a patch of ours from anybody
    #: else's.
    PATCH_KEY = os.path.join(os.path.expanduser("~"), ".config", "margyt",
                             "patch-public.b64")

    def write_patch_key(self) -> None:
        """Bake in the key that a patch has to be signed with.

        Without a key the build still works and patches are simply refused:
        an apk that would run anything handed to it over plain http is worse
        than an apk that cannot be patched at all.
        """
        key = ""
        path = os.environ.get("MARGYT_PATCH_KEY", self.PATCH_KEY)
        try:
            with open(path, encoding="utf-8") as handle:
                key = handle.read().strip()
        except OSError:
            pass

        out = os.path.join(self.root, "inject", "java", "cat", "narezany", "margyt",
                           "Patchkey.java")
        with open(out, "w", encoding="utf-8") as handle:
            handle.write(
                "package cat.narezany.margyt;\n\n"
                "/**\n"
                " * Written by the build. Do not edit.\n"
                " *\n"
                " * The public half of the key patches are signed with, in the\n"
                " * shape `KeyFactory` wants. Empty means this build takes no\n"
                " * patches at all, which is what a build made without the key\n"
                " * should do.\n"
                " */\n"
                "final class Patchkey {\n\n"
                "    private Patchkey() {}\n\n"
                "    static final String KEY = \"%s\";\n"
                "}\n" % key
            )
        self.detail("patches: %s" % ("signed with the key in %s" % path if key
                                     else "refused, no key to check them with"))

    def write_theme(self, arsc: Arsc) -> None:
        """The colours TikTok repaints when its own theme changes."""
        owned, safe = nightly.theme_colours(arsc, dexpatch.TIKTOK_PINK)
        self.detail("%d colours belong to the theme; %d of them mean nothing "
                    "else anywhere" % (len(owned), len(safe)))

        # sorted the way Java reads them, so the search in Nightly can use a
        # plain comparison rather than one the apk's minSdk may not have
        owned = sorted(owned, key=_signed)
        safe = sorted(safe, key=_signed)

        out = os.path.join(self.root, "inject", "java", "cat", "narezany", "margyt",
                           "Nightly.java")
        with open(out, "w", encoding="utf-8") as handle:
            handle.write(
                "package cat.narezany.margyt;\n\n"
                "/**\n"
                " * Written by the build. Do not edit.\n"
                " *\n"
                " * The colours in TikTok's own style table that the app repaints when\n"
                " * its theme changes -- the only ones `Themes` may touch.\n"
                " *\n"
                " * Two lists. OWNED is all of them, and is used where a colour arrives\n"
                " * as a theme colour: read from a theme attribute, or from a colour\n"
                " * resource. UNMISTAKABLE is the ones that appear nowhere else in the\n"
                " * apk, and those are repainted wherever they turn up -- including in\n"
                " * code that simply hands a number to a Paint. White is in the first\n"
                " * list and not the second, which is the whole reason there are two.\n"
                " */\n"
                "final class Nightly {\n\n"
                "    private Nightly() {}\n\n"
                "    static boolean owns(int colour) {\n"
                "        return has(OWNED, colour);\n"
                "    }\n\n"
                "    static boolean unmistakable(int colour) {\n"
                "        return has(UNMISTAKABLE, colour);\n"
                "    }\n\n"
                "    private static boolean has(int[] list, int colour) {\n"
                "        int low = 0;\n"
                "        int high = list.length - 1;\n"
                "        while (low <= high) {\n"
                "            int middle = (low + high) >>> 1;\n"
                "            int here = list[middle];\n"
                "            if (here == colour) return true;\n"
                "            if (here < colour) low = middle + 1;\n"
                "            else high = middle - 1;\n"
                "        }\n"
                "        return false;\n"
                "    }\n\n"
            )
            for name, values in (("OWNED", owned), ("UNMISTAKABLE", safe)):
                handle.write("    private static final int[] %s = {\n" % name)
                for i in range(0, len(values), 6):
                    row = ", ".join("0x%08X" % value for value in values[i:i + 6])
                    handle.write("        %s,\n" % row)
                handle.write("    };\n\n")
            handle.write("}\n")

    def add_assets(self, apk: Apk) -> None:
        """Files the mod reads at runtime, put in the apk beside TikTok's own.

        An asset rather than a download: the emoji font is a megabyte and a
        half and the cat's meow is twenty kilobytes, and a setting that says
        "trust me, it is coming" is not a setting. They are read straight out
        of the apk with no network involved.
        """
        for name in ("twemoji.ttf", "noto.ttf", "blobmoji.ttf", "meow.ogg"):
            path = os.path.join(self.root, "assets", name)
            with open(path, "rb") as handle:
                raw = handle.read()
            # stored rather than deflated: a font and a sound are both read
            # straight out of the apk, and neither can be read compressed
            apk.add("assets/margyt/" + name, raw, STORED)
            self.detail("assets/margyt/%s: %d kB" % (name, len(raw) // 1024))

    # --------------------------------------------------------- the icons

    #: what ships, in the order the settings show them
    ICONS = [
        ("grafiti", "Графити"),
        ("shine", "Блестящий"),
        ("tiktok", "Косплей на ТикТок"),
        ("materialyou", "Margyrial You"),
        ("material3", "Margyrial 3"),
        ("doodle", "Doodle"),
        ("glitch", "Глитч"),
        ("google", "Google"),
        ("dotted", "Точечная"),
        ("terminal", "Терминал"),
        ("yaai", "You are an idiot!"),
        ("deltamargyt", "Deltamargyt"),
        ("mteam", "Mteam"),
        ("deled", "сиводня йа нарисават картино"),
        ("margytcraft", "MargyTcraft"),
        ("tigr", "айй тигрр"),
        ("govno", "че за говно"),
        ("glamour", "Гламурная"),
    ]

    ICON_PACKAGE = 0x30
    ICON_ALIAS = "cat.narezany.margyt.Icon"

    def _icon_files(self) -> List[str]:
        return [os.path.join(self.root, "icons", "launcher", key + ".png")
                for key, _label in self.ICONS]

    def write_shots(self, manifest: Axml) -> None:
        """The icon list, as the mod sees it: names, components, thumbnails."""
        entry, _target = manifest_module.launcher_entry(manifest)

        rows = []
        for index, (key, label) in enumerate(self.ICONS):
            path = os.path.join(self.root, "icons", "launcher", key + ".png")
            with open(path, "rb") as handle:
                raw = handle.read()
            rows.append((key, label, "%s%d" % (self.ICON_ALIAS, index),
                         base64.b64encode(raw).decode("ascii")))

        out = os.path.join(self.root, "inject", "java", "cat", "narezany", "margyt",
                           "Shots.java")
        with open(out, "w", encoding="utf-8") as handle:
            handle.write(
                "package cat.narezany.margyt;\n\n"
                "/**\n"
                " * Written by the build. Do not edit.\n"
                " *\n"
                " * The icons this build ships with: what each is called, which\n"
                " * component wears it, and a picture of it for the settings to show.\n"
                " * DEFAULT is TikTok's own launcher entry, which is the one that is on\n"
                " * until another is chosen.\n"
                " */\n"
                "final class Shots {\n\n"
                "    private Shots() {}\n\n"
                "    static final String DEFAULT = \"%s\";\n\n"
                "    static final String[] DEFAULT_PNG = %s;\n\n"
                "    static final String[] KEYS = {\n"
                % (entry, _chunked(_thumbnail(os.path.join(self.root, artwork.MASTER_PNG))))
            )
            for key, _label, _component, _png in rows:
                handle.write("        \"%s\",\n" % key)
            handle.write("    };\n\n    static final String[] LABELS = {\n")
            for _key, label, _component, _png in rows:
                handle.write("        \"%s\",\n" % label)
            handle.write("    };\n\n    static final String[] COMPONENTS = {\n")
            for _key, _label, component, _png in rows:
                handle.write("        \"%s\",\n" % component)
            handle.write("    };\n\n    static final String[][] PNG = {\n")
            for _key, _label, _component, png in rows:
                handle.write("        %s,\n" % _chunked(png))
            handle.write("    };\n}\n")
        self.detail("%d icons to choose from" % len(rows))

    def add_icon_choices(self, apk: Apk, arsc: Arsc, manifest: Axml):
        """Put the icons in the apk, in the table, and in the manifest."""
        entry, target = manifest_module.launcher_entry(manifest)
        # the aliases take the app's own name; only the picture differs. It
        # said TikTok here, so picking any icon but the first also renamed the
        # thing on the home screen
        label = LABEL

        names = []
        for index, (key, _label) in enumerate(self.ICONS):
            path = "res/margyt/%s.png" % key
            with open(self._icon_files()[index], "rb") as handle:
                apk.add(path, handle.read())
            names.append((key, path))

        extended = resadd.add_files(arsc.build(), self.ICON_PACKAGE, "mipmap", names)
        self.detail("%d icons added to the resource table as package 0x%02X"
                    % (len(names), self.ICON_PACKAGE))

        for index, (key, name) in enumerate(self.ICONS):
            res_id = (self.ICON_PACKAGE << 24) | (1 << 16) | index
            manifest_module.add_icon_alias(
                manifest, "%s%d" % (self.ICON_ALIAS, index), target, res_id,
                label, enabled=False)
        self.detail("%d launcher entries declared, all off but %s"
                    % (len(self.ICONS), entry.rsplit(".", 1)[-1]))
        return extended

    def find_anchors(self, apk: Apk) -> None:
        """Find the methods whose signature is known and whose name is not."""
        dexes = {name: apk.read(name) for name in apk.names()
                 if name.endswith(".dex")}
        dexpatch.FOUND = dexpatch.find_statics(dexes)

        lines = []
        for label, _descriptor, _ours, _target in (
                dexpatch.DISCOVERED_STATICS + dexpatch.DISCOVERED_VIRTUALS):
            found = dexpatch.FOUND.get(label)
            if found is None:
                self.detail("%s: not found in this release" % label)
                continue
            owner, name = found
            self.detail("%s: %s->%s" % (label, owner, name))
            lines.append((label, owner, name))
        for label, expected, _descriptor, _ours, _target in dexpatch.DATE_STATICS:
            found = dexpatch.FOUND.get(label)
            if found is None:
                self.detail("%s: not found in this release" % label)
                lines.append((label, "X.0QeW", expected))
                continue
            owner, name = found
            self.detail("%s: %s->%s" % (label, owner, name))
            lines.append((label, owner, name))

        out = os.path.join(self.root, "inject", "java", "cat", "narezany", "margyt",
                           "Anchors.java")
        with open(out, "w", encoding="utf-8") as handle:
            handle.write(
                "package cat.narezany.margyt;\n\n"
                "/**\n"
                " * Written by the build. Do not edit.\n"
                " *\n"
                " * Where the methods the mod hands calls back to actually live in the\n"
                " * apk it was built from. Their signatures are in the patcher and are\n"
                " * the same every release; these names are not, which is why they are\n"
                " * found rather than written down.\n"
                " */\n"
                "final class Anchors {\n\n"
                "    private Anchors() {}\n"
            )
            for label, owner, name in lines:
                handle.write(
                    "\n    /** %s */\n"
                    "    static final String %s = \"%s\";\n"
                    "    static final String %s_METHOD = \"%s\";\n"
                    % (label, _constant(label), _java_name(owner),
                       _constant(label), name)
                )
            handle.write("}\n")

    # ------------------------------------------------------------------ dex

    def patch_dex_files(self, apk: Apk, api: int, literals: Dict[str, str]) -> None:
        smali = Smali(self.tools.smali, api)
        names = sorted(n for n in apk.names() if n.endswith(".dex"))
        candidates: List[str] = []
        for name in names:
            if dexpatch.interesting(apk.read(name), literals):
                candidates.append(name)
        self.detail("%d of %d dex files mention it" % (len(candidates), len(names)))

        telephony_labels = {label for label, _pattern, _target in dexpatch.rules()}
        calls = 0
        pink = 0
        total = 0
        for name in candidates:
            patched, counts = dexpatch.patch(
                apk.read(name), name, smali, os.path.join(self.workspace, "patch"), literals
            )
            if not counts:
                self.detail("%s: nothing to rewrite after all" % name)
                continue
            apk.replace(name, patched)
            hits = sum(counts.values())
            total += hits
            calls += sum(v for label, v in counts.items() if label in telephony_labels)
            pink += counts.get("the pink itself", 0)
            self.detail(
                "%s: %d (%s)"
                % (name, hits, ", ".join("%s x%d" % (k.split("(")[0], v)
                                         for k, v in sorted(counts.items())))
            )
        self.detail("%d telephony call sites, %d places the accent colour was written "
                    "down, %d rewrites in all" % (calls, pink, total))
        if not pink:
            self.detail("WARNING: the accent colour is not a constant in this apk any "
                        "more, so the colour picker will have nothing to change")
        if not calls:
            raise RuntimeError(
                "not one call site matched -- the method signatures have moved, "
                "and the mod would do nothing at all"
            )


def _tables(keys: List[int], moved: Dict[int, int]) -> str:
    """The two arrays, wrapped so the file stays readable."""
    if not keys:
        return "    static final int[] BAKED = {};\n    static final int[] FROM = {};\n"
    out = []
    for name, values in (("BAKED", keys), ("FROM", [moved[k] for k in keys])):
        lines = []
        for at in range(0, len(values), 6):
            lines.append("        " + ", ".join("0x%08X" % v for v in values[at:at + 6]))
        out.append("    static final int[] %s = {\n%s,\n    };\n" % (name, ",\n".join(lines)))
    return "\n".join(out)


def _chunked(encoded: str) -> str:
    """Base64 as a Java array of pieces.

    A string literal in a class file cannot be longer than 64k of utf-8, and
    an icon at 192 across can encode to more than that -- which is a build
    failure rather than anything subtle, and this is the whole of the fix.
    """
    pieces = [encoded[at:at + 20000] for at in range(0, len(encoded), 20000)]
    return "{" + ", ".join('"%s"' % piece for piece in pieces) + "}"


def _thumbnail(path: str) -> str:
    """A small png of a big one, as base64, for the settings to show."""
    from PIL import Image

    image = Image.open(path).convert("RGBA")
    image.thumbnail((192, 192), Image.LANCZOS)
    from io import BytesIO
    out = BytesIO()
    image.save(out, "PNG", optimize=True)
    return base64.b64encode(out.getvalue()).decode("ascii")


def _signed(value: int) -> int:
    return value - (1 << 32) if value >= (1 << 31) else value
