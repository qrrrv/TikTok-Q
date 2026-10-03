"""Rewriting the calls that ask the phone where it is.

Without root there is no Xposed, and without Xposed there is nothing to hook at
runtime -- so the calls are redirected in the bytecode instead. Each one becomes
a static call into the mod:

    invoke-virtual {v0}, Landroid/telephony/TelephonyManager;->getSimCountryIso()Ljava/lang/String;
    invoke-static  {v0}, Lcat/narezany/margyt/Region;->getSimCountryIso(Landroid/telephony/TelephonyManager;)Ljava/lang/String;

The instruction format (35c), the register count and the return type all match,
so nothing around the call has to be renumbered: the receiver just becomes the
first argument.

Targets are found by signature, never by offset or by file name, so a new
TikTok release does not move them. Of the apk's fifty-two dex files, the four
or five that mention telephony at all are the only ones taken apart; the rest
are copied across untouched, which is the difference between a build that takes
minutes and one that takes hours.
"""

from __future__ import annotations

import os
import re
import shutil
import struct
import subprocess
from typing import Dict, List, Optional, Tuple

TELEPHONY = "Landroid/telephony/TelephonyManager;"
REGION = "Lcat/narezany/margyt/Region;"
ACCENT = "Lcat/narezany/margyt/Accent;"
DOWNLOAD = "Lcat/narezany/margyt/Download;"
OFFLINE_LIMIT = "Lcat/narezany/margyt/OfflineLimit;"
FEED = "Lcat/narezany/margyt/Feed;"
PROFILE_TAB_COUNTS = "Lcat/narezany/margyt/ProfileTabCounts;"

# TikTok's own models. Every name here is a real one, read out of the apk's
# method and field tables rather than guessed, and each is answered by a static
# of ours with the receiver moved into the first argument -- the same 35c
# instruction, the same register count, the same return type.
VIDEO = "Lcom/ss/android/ugc/aweme/feed/model/Video;"
ACL = "Lcom/ss/android/ugc/aweme/feed/model/ACLCommonShare;"
AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"
PROFILE_USER = "Lcom/ss/android/ugc/profile/platform/base/data/UserProfileInfo;"
VIDEO_CONTROL = "Lcom/ss/android/ugc/aweme/feed/model/VideoControl;"
OFFLINE_MANAGER = "Lcom/ss/android/ugc/aweme/offlinemode/viewmodel/OfflineModeManagerVM;"
FEED_ITEM_LIST = "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;"
PHOTO_IMAGE = "Lcom/ss/android/ugc/aweme/feed/model/PhotoModeImageUrlModel;"
ACCOUNT_SERVICE = "Lcom/ss/android/ugc/aweme/IAccountUserService;"
ACCOUNT = "Lcat/narezany/margyt/Account;"
COMMENTS = "Lcat/narezany/margyt/Comments;"
COMMENT_IMAGE = "Lcom/ss/android/ugc/aweme/comment/model/CommentImageStruct;"
COMMENT = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"
COMMENT_STICKER = "Lcom/ss/android/ugc/aweme/comment/model/CommentStickerStruct;"
WATERMARK = "Lcat/narezany/margyt/Watermark;"
FLAGS = "Lcat/narezany/margyt/Flags;"
SOUND = "Lcat/narezany/margyt/Sound;"
BADGE = "Lcat/narezany/margyt/Badge;"
AVATARS = "Lcat/narezany/margyt/Avatars;"
STICKERS = "Lcat/narezany/margyt/Stickers;"
STREAKS = "Lcat/narezany/margyt/Streaks;"
STREAK_DATA = "Lcom/ss/android/ugc/aweme/im/streak/api/StreakData;"
STREAK_SERVICE = "Lcom/ss/android/ugc/aweme/im/streak/api/IStreakService;"
STICKER_ITEM = "Lcom/ss/android/ugc/aweme/im/common/model/StickerItem;"
STICKER_IMAGE = "Lcom/ss/android/ugc/aweme/im/common/model/StickerImage;"
STICKER_BASE = "Lcom/ss/android/ugc/aweme/im/common/model/StickerBase;"
STICKER_CLICK = "Lcom/ss/android/ugc/aweme/im/messagelist/api/ability/MessageListStickerClickAbility;"
STICKER_TEMPLATE = "Lcom/ss/android/ugc/aweme/im/message/template/card/StickerTemplate;"
TEXT_VIEW = "Landroid/widget/TextView;"
# TikTok writes almost everything with its own text view, and a call is
# compiled against the type it is written on -- so a rule that names
# TextView never matches one of these. The mod takes them as TextView all
# the same, which is what they are.
TUX_TEXT = "Lcom/bytedance/tux/input/TuxTextView;"
CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
SEEKBAR = "Lcat/narezany/margyt/Seekbar;"
FONTS = "Lcat/narezany/margyt/Fonts;"
RATE = "Lcat/narezany/margyt/Rate;"
TYPEFACE = "Landroid/graphics/Typeface;"
PAINT_CLASS = "Landroid/graphics/Paint;"
MUSIC = "Lcom/ss/android/ugc/aweme/music/model/Music;"

# A method whose name is real on a class whose name is not. Matching the owner
# would mean writing down an obfuscated name that changes every release, so the
# owner is left open and the receiver arrives as a plain Object -- which is why
# the mod calls the original back by reflection rather than directly.
WILD_SOURCES: List[Tuple[str, str, str, str]] = [
    ("setSeekBarShowType", "(I)V", "(Ljava/lang/Object;I)V", SEEKBAR),
    # profile tab binding: the payload class is obfuscated and changes between
    # TikTok releases, so both the receiver and payload are passed as Object
    ("Yc0", "(L*;ILandroid/view/View;)V",
     "(Ljava/lang/Object;Ljava/lang/Object;ILandroid/view/View;)V", PROFILE_TAB_COUNTS),
]

# A sticker touched in a conversation.
#
# The interface is MessageListStickerClickAbility and the type it is handed is
# StickerTemplate, both real names -- but a call site names whatever static
# type it is holding, which is the class implementing the interface, so the
# owner is left open. What is written down instead is each method's exact
# shape, obfuscated parameter types and all: those move between releases, and a
# release that moves them leaves the rules matching nothing, which is a feature
# that does not appear rather than an app that breaks.
#
# Everything but the sticker itself arrives as Object -- a reference is a
# reference as far as the verifier is concerned -- which is what keeps a fifth
# obfuscated name out of the mod's own source.
_TAP = "Landroid/view/View;LX/1CpA;LX/13Vd;" + STICKER_TEMPLATE
_SHEET = ("Landroid/view/View;Landroidx/fragment/app/FragmentManager;LX/13Vd;"
          + STICKER_TEMPLATE)
_INNER = "LX/1CpA;LX/13Vd;" + STICKER_TEMPLATE
_CORNER = "Lcom/ss/android/ugc/aweme/views/RoundingCornerLayout;" + STICKER_TEMPLATE
_ANY = "Ljava/lang/Object;"

WILD_SOURCES += [
    ("UP", "(%s)V" % _TAP, "(%s%s)V" % (_ANY * 4, STICKER_TEMPLATE), STICKERS),
    ("tR1", "(%s)V" % _TAP, "(%s%s)V" % (_ANY * 4, STICKER_TEMPLATE), STICKERS),
    ("dy1", "(%s)V" % _SHEET, "(%s%s)V" % (_ANY * 4, STICKER_TEMPLATE), STICKERS),
    ("Yt1", "(%s)V" % _INNER, "(%s%s)V" % (_ANY * 3, STICKER_TEMPLATE), STICKERS),
    ("XQ1", "(%s)V" % _CORNER, "(%s%s)V" % (_ANY * 2, STICKER_TEMPLATE), STICKERS),
]

# TikTok's A/B facade. The class name is real; the method names are what the
# obfuscator made of them in 46.9.42, one per type, each taking the flag's name
# and what to answer when the server said nothing. A release that renames them
# leaves the rules matching nothing, which turns the overrides off and breaks
# no part of the app.
SETTINGS_MANAGER = "Lcom/bytedance/ies/abmock/SettingsManager;"
AB_READERS: List[Tuple[str, str, str]] = [
    ("LIZ", "Ljava/lang/String;Z", "Z"),
    ("LJ", "Ljava/lang/String;I", "I"),
    ("LJFF", "Ljava/lang/String;J", "J"),
    ("LJI", "Ljava/lang/String;Ljava/lang/String;", "Ljava/lang/String;"),
    ("LIZJ", "Ljava/lang/String;F", "F"),
    ("LIZIZ", "Ljava/lang/String;D", "D"),
]
CANVAS = "Landroid/graphics/Canvas;"

# Rules that apply inside one class and nowhere else, found by a string the
# class carries rather than by its name.
#
# The stamp on a saved picture is assembled on the phone -- text and a logo
# drawn into a bitmap of their own -- and then put on the picture with a single
# Canvas.drawBitmap. Redirecting every drawBitmap in the apk would be absurd;
# redirecting the one in the class that builds the label is a rewrite of a
# single instruction. The class is X.0Hnc in 46.9.42 and will be something else
# in 46.10, but the marker in its template stays.
#
# anchor, owner, method, its descriptor, ours, the class ours lives in
# A static TikTok's obfuscation renamed but could not disguise: what it takes
# is the framework's own types and the app's own models, and no other method in
# the apk takes that. The build finds the one class that declares it, writes
# the name down for the mod to hand the call back through, and rewrites every
# call to it. Nothing obfuscated is written here, and a release that shuffles
# the names is simply found again on the next build.
#
# label, the descriptor to look for, what the mod calls it, where that lives
COMMENT_TAP = ("(Landroid/view/View;%s ZLjava/lang/String;Ljava/util/Map;"
               "Ljava/lang/String;)V" % COMMENT_STICKER).replace("; Z", ";Z")

DISCOVERED_STATICS: List[Tuple[str, str, str, str]] = [
    # a sticker in a comment, tapped. In a conversation the tap goes through an
    # interface the mod can name; here it goes to a static on a class with no
    # name worth writing down -- but it takes the view that was touched and the
    # sticker that was in it, and nothing else in the apk takes that pair.
    ("comment sticker tapped", COMMENT_TAP, "stickerTapped", COMMENTS),
]

STICKER_ITEM_D = "Lcom/ss/android/ugc/aweme/im/common/model/StickerItem;"
FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

#: the sheet that opens on a comment's sticker -- Share, Save, Use. Found the
#: same way, by what it takes: a sticker and the view it was touched in. The
#: receiver comes through as an Object and the call is handed back by
#: reflection, so the class it lives on is never written down here either.
COMMENT_SHEET = ("(Ljava/lang/String;%s Landroid/view/View;ZLjava/lang/String;"
                 "Ljava/util/Map;%s %s %s)V"
                 % (STICKER_ITEM_D, FUNCTION0, FUNCTION0, FUNCTION0)).replace(" ", "")

DISCOVERED_VIRTUALS: List[Tuple[str, str, str, str]] = [
    ("comment sticker sheet", COMMENT_SHEET, "stickerSheet", COMMENTS),
]

# filled in by the build: label -> (owner, the name it carries this release)
FOUND: Dict[str, Tuple[str, str]] = {}


VIEW = "Landroid/view/View;"
LONG_CLICK = "Landroid/view/View$OnLongClickListener;"

ANCHORED_SOURCES: List[Tuple[str, str, str, str, str, str]] = [
    # A sticker in a comment already answers a long press -- TikTok sets its
    # own listener on it. So the mod does not add a gesture, it wraps the one
    # that is there: the press still does what it did, and the offer to save
    # appears beside it.
    #
    # The class is anchored by a line TikTok logs while binding a sticker,
    # which no other class in the apk carries. Anchoring matters here more than
    # anywhere: `setOnLongClickListener` is the framework's, and rewriting
    # every call to it would mean wrapping several thousand unrelated views.
    ("bindSticker: ", VIEW, "setOnLongClickListener",
     "(%s)V" % LONG_CLICK, "(%s%s)V" % (VIEW, LONG_CLICK), COMMENTS),
    ("[tiktok_logo]", CANVAS, "drawBitmap",
     "(Landroid/graphics/Bitmap;FFLandroid/graphics/Paint;)V",
     "(%sLandroid/graphics/Bitmap;FFLandroid/graphics/Paint;)V" % CANVAS, WATERMARK),
]
STRING = "Ljava/lang/String;"
URL_MODEL = "Lcom/ss/android/ugc/aweme/base/model/UrlModel;"
BOXED_BOOLEAN = "Ljava/lang/Boolean;"
LIST = "Ljava/util/List;"

# owner, method, its descriptor, ours, the class ours lives in
SUBSCRIPTIONS = "Landroid/telephony/SubscriptionManager;"

MODEL_SOURCES: List[Tuple[str, str, str, str, str]] = [
    # TikTok writes the chosen offline cache size through this method. Keep
    # the native UI and replace only the final count with the user's value.
    (OFFLINE_MANAGER, "B83", "(I)V", "setCacheCount", OFFLINE_LIMIT),
    # How many SIMs the system says are in the phone. Answering the six
    # questions about the card is not enough on a phone with no card in it:
    # the app asks how many there are first, gets nothing, and never asks the
    # rest. These say one, which is what a phone with a card says.
    (TELEPHONY, "getPhoneCount", "()I", "(%s)I" % TELEPHONY, REGION),
    (TELEPHONY, "getActiveModemCount", "()I", "(%s)I" % TELEPHONY, REGION),
    (TELEPHONY, "getPhoneType", "()I", "(%s)I" % TELEPHONY, REGION),
    (SUBSCRIPTIONS, "getActiveSubscriptionInfoCount", "()I",
     "(%s)I" % SUBSCRIPTIONS, REGION),
    # the save button's address: stamped, and the clean one beside it
    (VIDEO, "getDownloadAddr", "()%s" % URL_MODEL, "(%s)%s" % (VIDEO, URL_MODEL), DOWNLOAD),
    # what the post says may be done with it, which TikTok reads before it
    # offers a download at all
    (ACL, "getCode", "()I", "(%s)I" % ACL, DOWNLOAD),
    (ACL, "getShowType", "()I", "(%s)I" % ACL, DOWNLOAD),
    (ACL, "getTranscode", "()I", "(%s)I" % ACL, DOWNLOAD),
    # the ban on saving, on the post and on the account that made it
    (AWEME, "isPreventDownload", "()Z", "(%s)Z" % AWEME, DOWNLOAD),
    (USER, "isPreventDownload", "()Z", "(%s)Z" % USER, DOWNLOAD),
    # the page of the feed, before anything has looked at it
    (FEED_ITEM_LIST, "getItems", "()%s" % LIST, "(%s)%s" % (FEED_ITEM_LIST, LIST), FEED),
    # who is signed in. The mod does not ask -- there is no unobfuscated way to
    # reach the service -- so it listens instead: the app asks often enough,
    # and the answer goes past on its way back.
    (ACCOUNT_SERVICE, "getCurUserId", "()%s" % STRING,
     "(%s)%s" % (ACCOUNT_SERVICE, STRING), ACCOUNT),
    (ACCOUNT_SERVICE, "getCurSecUserId", "()%s" % STRING,
     "(%s)%s" % (ACCOUNT_SERVICE, STRING), ACCOUNT),
    # which sticker a comment is carrying, read as the comment is bound: the
    # view gets its long press wrapped a moment later, and this is what says
    # what that view is showing
    (COMMENT, "getStickerStruct", "()%s" % COMMENT_STICKER,
     "(Ljava/lang/Object;)%s" % COMMENT_STICKER, COMMENTS),
    (COMMENT_IMAGE, "getCropUrl", "()%s" % URL_MODEL,
     "(%s)%s" % (COMMENT_IMAGE, URL_MODEL), COMMENTS),
    (COMMENT_IMAGE, "getOriginUrl", "()%s" % URL_MODEL,
     "(%s)%s" % (COMMENT_IMAGE, URL_MODEL), COMMENTS),
    # a name, and every place that writes one. The mark rides out on the name
    # and becomes a picture on the way into the view that shows it
    (USER, "getNickname", "()Ljava/lang/String;",
     "(%s)Ljava/lang/String;" % USER, BADGE),
    # and the model a profile switches to once it has finished loading, which
    # is why a badge used to appear while the profile loaded and then go away
    (PROFILE_USER, "getNickname", "()Ljava/lang/String;",
     "(%s)Ljava/lang/String;" % PROFILE_USER, BADGE),

    # the avatar, at the sizes the app actually asks for: the mod does not need
    # to know whose profile is open, only which picture was last wanted
    (USER, "getAvatarLarger", "()%s" % URL_MODEL, "(%s)%s" % (USER, URL_MODEL), AVATARS),
    (USER, "getAvatar300", "()%s" % URL_MODEL, "(%s)%s" % (USER, URL_MODEL), AVATARS),
    (USER, "getAvatarMedium", "()%s" % URL_MODEL, "(%s)%s" % (USER, URL_MODEL), AVATARS),
    # the typeface, wherever TikTok chooses one for itself. Most views never
    # do -- they inherit it -- and those are reached on the way text goes in,
    # which the mod is already standing in for the badges
    (TEXT_VIEW, "setTypeface", "(%s)V" % TYPEFACE,
     "(%s%s)V" % (TEXT_VIEW, TYPEFACE), FONTS),
    (TEXT_VIEW, "setTypeface", "(%sI)V" % TYPEFACE,
     "(%s%sI)V" % (TEXT_VIEW, TYPEFACE), FONTS),
    (TUX_TEXT, "setTypeface", "(%s)V" % TYPEFACE,
     "(%s%s)V" % (TEXT_VIEW, TYPEFACE), FONTS),
    (TUX_TEXT, "setTypeface", "(%sI)V" % TYPEFACE,
     "(%s%sI)V" % (TEXT_VIEW, TYPEFACE), FONTS),
    (PAINT_CLASS, "setTypeface", "(%s)%s" % (TYPEFACE, TYPEFACE),
     "(%s%s)%s" % (PAINT_CLASS, TYPEFACE, TYPEFACE), FONTS),

    # the frame rate TikTok asks the system for, which is how a 120 Hz phone
    # ends up running the feed at 60. A window can only prefer a rate, and an
    # app that keeps asking for another one wins unless it asks through here.
    ("Landroid/view/Surface;", "setFrameRate", "(FI)V",
     "(Landroid/view/Surface;FI)V", RATE),
    ("Landroid/view/Surface;", "setFrameRate", "(FII)V",
     "(Landroid/view/Surface;FII)V", RATE),

    (TEXT_VIEW, "setText", "(%s)V" % CHAR_SEQUENCE,
     "(%s%s)V" % (TEXT_VIEW, CHAR_SEQUENCE), BADGE),
    (TUX_TEXT, "setText", "(%s)V" % CHAR_SEQUENCE,
     "(%s%s)V" % (TEXT_VIEW, CHAR_SEQUENCE), BADGE),
    (TUX_TEXT, "setText", "(%sLandroid/widget/TextView$BufferType;)V" % CHAR_SEQUENCE,
     "(%s%sLandroid/widget/TextView$BufferType;)V" % (TEXT_VIEW, CHAR_SEQUENCE), BADGE),
    (TEXT_VIEW, "setText", "(%sLandroid/widget/TextView$BufferType;)V" % CHAR_SEQUENCE,
     "(%s%sLandroid/widget/TextView$BufferType;)V" % (TEXT_VIEW, CHAR_SEQUENCE), BADGE),


    # Which conversations have a streak going. `StreakData` holds the answer
    # and every one of its fields is named plainly, but nothing in the app ever
    # reads those fields, so there is nothing to listen to there. What the app
    # does do, constantly, is ask its own streak service about a conversation --
    # and `IStreakService` is a real name with real signatures. So the mod
    # listens to the questions instead of the answers: every conversation the
    # app asks about is one the mod can then ask about itself.
    #
    # The three method names are this release's, not the app's forever. They
    # are the one version-shaped thing in the streak feature, they live here
    # rather than in the Java, and the build counts what each of them matched.
    (STREAK_SERVICE, ("J", "streakOf"), "(%sZ)%s" % (STRING, STREAK_DATA),
     "(%s%sZ)%s" % (STREAK_SERVICE, STRING, STREAK_DATA), STREAKS),
    (STREAK_SERVICE, ("a0", "hasStreak"), "(%s)Z" % STRING,
     "(%s%s)Z" % (STREAK_SERVICE, STRING), STREAKS),
    (STREAK_SERVICE, ("h0", "showsStreak"), "(%sZ)Z" % STRING,
     "(%s%sZ)Z" % (STREAK_SERVICE, STRING), STREAKS),
    # every other question the app asks about one conversation, because the
    # first three between them only ever turned up a single conversation and a
    # feature that only knows about one chat is no feature
    (STREAK_SERVICE, ("w", "streakCount"), "(%s)I" % STRING,
     "(%s%s)I" % (STREAK_SERVICE, STRING), STREAKS),
    (STREAK_SERVICE, ("X", "asksAbout"), "(%s)Z" % STRING,
     "(%s%s)Z" % (STREAK_SERVICE, STRING), STREAKS),
    (STREAK_SERVICE, ("Y", "asksAboutToo"), "(%s)Z" % STRING,
     "(%s%s)Z" % (STREAK_SERVICE, STRING), STREAKS),
    (STREAK_SERVICE, ("l0", "streakState"), "(%s)Ljava/lang/Integer;" % STRING,
     "(%s%s)Ljava/lang/Integer;" % (STREAK_SERVICE, STRING), STREAKS),
    (STREAK_SERVICE, ("O", "streakText"), "(%s)%s" % (STRING, STRING),
     "(%s%s)%s" % (STREAK_SERVICE, STRING, STRING), STREAKS),

    # a sound pulled for copyright: the video stays and these four mute it
    (MUSIC, "available", "()Z", "(%s)Z" % MUSIC, SOUND),
    (MUSIC, "getMusicStatus", "()I", "(%s)I" % MUSIC, SOUND),
    (MUSIC, "isMuteShare", "()Z", "(%s)Z" % MUSIC, SOUND),
    (MUSIC, "getMuteType", "()I", "(%s)I" % MUSIC, SOUND),
]

# Statics of TikTok's own, which is how the flags are actually read: there is
# no receiver, so the instruction keeps its shape and only the class it lands
# in changes -- and the mod calls the original back by the same static.
MODEL_STATICS: List[Tuple[str, str, str, str, str]] = [
    (SETTINGS_MANAGER, (name, "flag"), "(%s)%s" % (args, kind),
     "(%s)%s" % (args, kind), FLAGS)
    for name, args, kind in AB_READERS
]

# The date beside an author's name, which TikTok draws only when the video was
# opened from a profile. Five questions decide it -- each "is this where it was
# opened from one of the profile ones" -- and the mod answers them.
#
DATES = "Lcat/narezany/margyt/Dates;"
# The owner is obfuscated and changes between TikTok releases. Keep the
# method names and signatures (the stable part of this feature), discover the
# owner during the build, and write it to Anchors.java.
DATE_STATICS: List[Tuple[str, str, str, str, str]] = [
    ("date gate LIZ", "LIZ", "(%s)Z" % STRING, "fromProfile", DATES),
    ("date gate LIZIZ", "LIZIZ", "(%s)Z" % STRING, "fromProfileToo", DATES),
    ("date gate LIZJ", "LIZJ", "(%s)Z" % STRING, "fromProfileAlso", DATES),
    ("date gate LIZLLL", "LIZLLL", "(%s)Z" % STRING, "fromProfileAsWell", DATES),
    ("date gate LJFF", "LJFF", "(%s)Z" % STRING, "fromProfileOrOther", DATES),
]

# The class that held the five gates in the release this was first written
# against. A tie-breaker and nothing more: the build never trusts it unless
# the class also carries all five.
DATE_OWNER_HINT = "LX/0QeW;"

# What the last search for the gates found, one line each, for the build log.
DATE_REPORT: List[str] = []

# and which subscription the system calls the default, which is -1 when there
# is no card at all -- code that asks usually gives up on the spot
MODEL_STATICS += [
    (SUBSCRIPTIONS, "getDefaultDataSubscriptionId", "()I", "()I", REGION),
    (SUBSCRIPTIONS, "getDefaultVoiceSubscriptionId", "()I", "()I", REGION),
    (SUBSCRIPTIONS, "getDefaultSmsSubscriptionId", "()I", "()I", REGION),
    (SUBSCRIPTIONS, "getActiveDataSubscriptionId", "()I", "()I", REGION),
]

# owner, field, its type, ours, the class ours lives in. A field rather than a
# getter, so the read is an instruction of a different shape and becomes two.
FIELD_SOURCES: List[Tuple[str, str, str, str, str]] = [
    (VIDEO_CONTROL, "allowDownload", BOXED_BOOLEAN, "allowDownload", DOWNLOAD),
    # a slideshow is not a video and never goes near getDownloadAddr: its
    # images carry the stamp themselves, with the clean one beside them
    # every sticker the app touches, so the settings have something to offer as
    # the one to send. `currentImage()` reads like the right place and is not:
    # five call sites in the whole apk, none of them on the way to a screen.
    # This field is read in a couple of hundred, which is what drawing one
    # actually looks like.
    (STICKER_ITEM, "stickerBase", STICKER_BASE, "stickerBase", STREAKS),
    (PHOTO_IMAGE, "ownerWatermarkImage", URL_MODEL, "ownerWatermarkImage", DOWNLOAD),
    (PHOTO_IMAGE, "userWatermarkImage", URL_MODEL, "userWatermarkImage", DOWNLOAD),
]

# The pink TikTok is built around. Most of the places it is drawn hold it as a
# plain constant in the bytecode, so each of those becomes a call into the mod
# and the colour turns into something a person can change. The build counts
# what it found and stops if the answer is none: a colour picker that changes
# nothing is worse than no colour picker.
TIKTOK_PINK = 0xFFFE2C55

# Colours that arrive through the framework rather than as a constant. Same
# rewrite as the telephony calls: the receiver becomes the first argument.
#
# Reading a colour is only half of it. One that was never read -- computed,
# blended, carried in from somewhere the mod cannot see -- still has to be
# applied to something before it reaches the screen, and the places it can be
# applied are few and have real names. Those are the second half of the list,
# and they are what reaches the parts a resource table never could.
COLOUR_SOURCES: List[Tuple[str, str, str]] = [
    ("Landroid/content/res/Resources;", "getColor",
     "(I)I", "(Landroid/content/res/Resources;I)I"),
    ("Landroid/content/res/Resources;", "getColor",
     "(ILandroid/content/res/Resources$Theme;)I",
     "(Landroid/content/res/Resources;ILandroid/content/res/Resources$Theme;)I"),
    ("Landroid/content/res/TypedArray;", "getColor",
     "(II)I", "(Landroid/content/res/TypedArray;II)I"),
    ("Landroid/content/Context;", "getColor",
     "(I)I", "(Landroid/content/Context;I)I"),

    # A background written in a layout is not a colour by the time the app sees
    # it -- the framework has already wrapped it in a ColorDrawable, and every
    # rule above looks straight past it. This is the way most of TikTok's
    # screens get their background, which is why the comments, the inbox and a
    # conversation kept TikTok's own while everything else moved.
    ("Landroid/content/res/TypedArray;", "getDrawable",
     "(I)Landroid/graphics/drawable/Drawable;",
     "(Landroid/content/res/TypedArray;I)Landroid/graphics/drawable/Drawable;"),
    ("Landroid/view/View;", "setBackgroundResource",
     "(I)V", "(Landroid/view/View;I)V"),

    # where a picture is asked for by number, which is where a texture pack
    # gets to answer instead
    # (this list's replacements all land in Accent; the frame rate has its own
    # class and so is written as a wild rule further down)
    ("Landroid/content/Context;", "getDrawable",
     "(I)Landroid/graphics/drawable/Drawable;",
     "(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;"),
    ("Landroid/content/res/Resources;", "getDrawable",
     "(I)Landroid/graphics/drawable/Drawable;",
     "(Landroid/content/res/Resources;I)Landroid/graphics/drawable/Drawable;"),
    ("Landroid/content/res/Resources;", "getDrawable",
     "(ILandroid/content/res/Resources$Theme;)Landroid/graphics/drawable/Drawable;",
     "(Landroid/content/res/Resources;ILandroid/content/res/Resources$Theme;)"
     "Landroid/graphics/drawable/Drawable;"),
    ("Landroid/widget/ImageView;", "setImageResource",
     "(I)V", "(Landroid/widget/ImageView;I)V"),

    # A Lottie animation -- the heart, the loading spinners -- is a json file
    # rather than a picture, read as a stream. Which makes it the one thing in
    # a texture pack somebody can edit in a text editor.
    ("Landroid/content/res/Resources;", "openRawResource",
     "(I)Ljava/io/InputStream;",
     "(Landroid/content/res/Resources;I)Ljava/io/InputStream;"),
    ("Landroid/content/res/AssetManager;", "open",
     "(Ljava/lang/String;)Ljava/io/InputStream;",
     "(Landroid/content/res/AssetManager;Ljava/lang/String;)Ljava/io/InputStream;"),

    # how far down a view is turned. TikTok fades its own overlay in and out
    # all the time -- when a video pauses, when a panel opens -- and every one
    # of those put back the brightness the mod had taken off
    ("Landroid/view/View;", "setAlpha", "(F)V", "(Landroid/view/View;F)V"),

    # a colour that comes with a state to go with it: enabled, pressed, chosen
    ("Landroid/content/res/Resources;", "getColorStateList",
     "(I)Landroid/content/res/ColorStateList;",
     "(Landroid/content/res/Resources;I)Landroid/content/res/ColorStateList;"),
    ("Landroid/content/res/TypedArray;", "getColorStateList",
     "(I)Landroid/content/res/ColorStateList;",
     "(Landroid/content/res/TypedArray;I)Landroid/content/res/ColorStateList;"),

    # where a colour is put to use: the brush, the shape, the tint, the text
    ("Landroid/view/Window;", "setStatusBarColor",
     "(I)V", "(Landroid/view/Window;I)V"),
    ("Landroid/view/Window;", "setNavigationBarColor",
     "(I)V", "(Landroid/view/Window;I)V"),
    ("Landroid/widget/TextView;", "setHintTextColor",
     "(I)V", "(Landroid/widget/TextView;I)V"),
    ("Landroid/graphics/drawable/Drawable;", "setTint",
     "(I)V", "(Landroid/graphics/drawable/Drawable;I)V"),
    ("Landroid/graphics/drawable/GradientDrawable;", "setColors",
     "([I)V", "(Landroid/graphics/drawable/GradientDrawable;[I)V"),
    ("Landroid/graphics/Paint;", "setColor",
     "(I)V", "(Landroid/graphics/Paint;I)V"),
    ("Landroid/graphics/drawable/GradientDrawable;", "setColor",
     "(I)V", "(Landroid/graphics/drawable/GradientDrawable;I)V"),
    ("Landroid/widget/ImageView;", "setColorFilter",
     "(I)V", "(Landroid/widget/ImageView;I)V"),
    ("Landroid/widget/ImageView;", "setColorFilter",
     "(ILandroid/graphics/PorterDuff$Mode;)V",
     "(Landroid/widget/ImageView;ILandroid/graphics/PorterDuff$Mode;)V"),
    ("Landroid/widget/TextView;", "setTextColor",
     "(I)V", "(Landroid/widget/TextView;I)V"),
    ("Lcom/bytedance/tux/input/TuxTextView;", "setTextColor",
     "(I)V", "(Landroid/widget/TextView;I)V"),
    ("Lcom/bytedance/tux/input/TuxTextView;", "setHintTextColor",
     "(I)V", "(Landroid/widget/TextView;I)V"),
    ("Landroid/view/View;", "setBackgroundColor",
     "(I)V", "(Landroid/view/View;I)V"),
    # TikTok's own icon view, and a real name at that
    ("Lcom/bytedance/tux/icon/TuxIconView;", "setColor",
     "(I)V", "(Lcom/bytedance/tux/icon/TuxIconView;I)V"),
]

# A static of the framework's own: no receiver, so the call keeps its shape
# exactly and only the class it lands in changes.
COLOUR_STATICS: List[Tuple[str, str, str]] = [
    ("Landroid/content/res/ColorStateList;", "valueOf",
     "(I)Landroid/content/res/ColorStateList;"),
]

# method name -> (descriptor as TikTok calls it, descriptor of the static that
# replaces it -- the same, with the receiver moved into the arguments)
TARGETS: List[Tuple[str, str, str]] = [
    ("getSimCountryIso", "()Ljava/lang/String;", "(%s)Ljava/lang/String;" % TELEPHONY),
    ("getNetworkCountryIso", "()Ljava/lang/String;", "(%s)Ljava/lang/String;" % TELEPHONY),
    ("getSimOperator", "()Ljava/lang/String;", "(%s)Ljava/lang/String;" % TELEPHONY),
    ("getNetworkOperator", "()Ljava/lang/String;", "(%s)Ljava/lang/String;" % TELEPHONY),
    ("getSimOperatorName", "()Ljava/lang/String;", "(%s)Ljava/lang/String;" % TELEPHONY),
    ("getNetworkOperatorName", "()Ljava/lang/String;", "(%s)Ljava/lang/String;" % TELEPHONY),
    ("getSimState", "()I", "(%s)I" % TELEPHONY),
    ("getSimState", "(I)I", "(%sI)I" % TELEPHONY),
    ("hasIccCard", "()Z", "(%s)Z" % TELEPHONY),
    ("isNetworkRoaming", "()Z", "(%s)Z" % TELEPHONY),
    ("getSimCarrierId", "()I", "(%s)I" % TELEPHONY),
]


# Methods that have to answer no, whatever they would have worked out.
#
# TikTok signs in with Google two ways: through Play Services, and through the
# browser with AppAuth and a custom-scheme redirect. It asks the first one
# whether it is available and only falls back to the second when it is not.
#
# For anything built here the Play Services way cannot work at all: Google
# checks the package name against the certificate's SHA-1, and the certificate
# is no longer TikTok's. Left alone it fails with a developer error and no way
# forward. So the provider that speaks to Play Services reports itself
# unavailable, and the app takes its own fallback -- the browser, which checks
# nothing but who receives the redirect.
#
# The class name is a real one, not an obfuscated one, which is what makes this
# safe to anchor on.
FORCED_FALSE: List[Tuple[str, str]] = [
    ("com/bytedance/lobby/google/GoogleAuth", "isAvailable()Z"),
]


def rules() -> List[Tuple[str, "re.Pattern[str]", str]]:
    out = []
    for name, original, replacement in TARGETS:
        pattern = re.compile(
            r"invoke-virtual(/range)? (\{[^}]*\}), %s->%s%s"
            % (re.escape(TELEPHONY), name, re.escape(original))
        )
        target = r"invoke-static\1 \2, %s->%s%s" % (REGION, name, replacement)
        out.append((name + original, pattern, target))
    return out


def accent_rules() -> List[Tuple[str, "re.Pattern[str]", str]]:
    """The pink, wherever the bytecode spells it out or asks for it."""
    out = []
    literal = "-0x%x" % ((1 << 32) - TIKTOK_PINK) if TIKTOK_PINK > 0x7FFFFFFF \
        else "0x%x" % TIKTOK_PINK
    # const vX, -0x1d3ab  ->  a call, and the answer in the same register
    out.append((
        "the pink itself",
        re.compile(r"^(\s*)const ([vp]\d+), %s$" % re.escape(literal), re.MULTILINE),
        r"\1invoke-static {}, %s->accent()I\n\n\1move-result \2" % ACCENT,
    ))
    for owner, name, original, replacement in COLOUR_SOURCES:
        out.append((
            "%s->%s%s" % (owner.split("/")[-1][:-1], name, original),
            re.compile(r"invoke-virtual(/range)? (\{[^}]*\}), %s->%s%s"
                       % (re.escape(owner), name, re.escape(original))),
            r"invoke-static\1 \2, %s->%s%s" % (ACCENT, name, replacement),
        ))
    for owner, name, signature in COLOUR_STATICS:
        out.append((
            "%s->%s" % (owner.split("/")[-1][:-1], name),
            re.compile(r"invoke-static(/range)? (\{[^}]*\}), %s->%s%s"
                       % (re.escape(owner), name, re.escape(signature))),
            r"invoke-static\1 \2, %s->%s%s" % (ACCENT, name, signature),
        ))
    return out


def model_rules() -> List[Tuple[str, "re.Pattern[str]", str]]:
    """Calls on TikTok's own models, answered by the mod instead."""
    out = []
    for owner, name, original, replacement, target in MODEL_SOURCES:
        # a pair when what the mod calls it differs from what TikTok does:
        # the app's obfuscated name on the way in, a readable one on the way out
        theirs, ours = name if isinstance(name, tuple) else (name, name)
        out.append((
            "%s->%s" % (owner.rsplit("/", 1)[-1][:-1], theirs),
            # an interface call is the same 35c instruction under another
            # mnemonic, and a service reached through one is still a receiver
            re.compile(r"invoke-(?:virtual|interface)(/range)? (\{[^}]*\}), %s->%s%s"
                       % (re.escape(owner), theirs, re.escape(original))),
            r"invoke-static\1 \2, %s->%s%s" % (target, ours, replacement),
        ))
    for owner, name, original, replacement, target in MODEL_STATICS:
        theirs, ours = name if isinstance(name, tuple) else (name, name)
        out.append((
            "%s->%s" % (owner.rsplit("/", 1)[-1][:-1], theirs),
            re.compile(r"invoke-static(/range)? (\{[^}]*\}), %s->%s%s"
                       % (re.escape(owner), theirs, re.escape(original))),
            r"invoke-static\1 \2, %s->%s%s" % (target, ours, replacement),
        ))
    for label, _theirs, descriptor, ours, target in DATE_STATICS:
        found = FOUND.get(label)
        if found is None:
            continue
        owner, theirs = found
        out.append((
            label,
            re.compile(r"invoke-static(/range)? (\{[^}]*\}), %s->%s%s"
                       % (re.escape(owner), re.escape(theirs), re.escape(descriptor))),
            r"invoke-static\1 \2, %s->%s%s" % (target, ours, descriptor),
        ))
    for label, descriptor, ours, target in DISCOVERED_VIRTUALS:
        found = FOUND.get(label)
        if found is None:
            continue
        owner, theirs = found
        # the receiver is already the first register of an invoke-virtual, so
        # the instruction keeps its registers and only its kind changes; the
        # mod takes that receiver as a plain Object and hands the call back
        out.append((
            label,
            re.compile(r"invoke-virtual(/range)? (\{[^}]*\}), %s->%s%s"
                       % (re.escape(owner), re.escape(theirs), re.escape(descriptor))),
            r"invoke-static\1 \2, %s->%s%s"
            % (target, ours, "(Ljava/lang/Object;" + descriptor[1:]),
        ))
    for label, descriptor, ours, target in DISCOVERED_STATICS:
        found = FOUND.get(label)
        if found is None:
            continue
        owner, theirs = found
        out.append((
            label,
            re.compile(r"invoke-static(/range)? (\{[^}]*\}), %s->%s%s"
                       % (re.escape(owner), re.escape(theirs), re.escape(descriptor))),
            r"invoke-static\1 \2, %s->%s%s" % (target, ours, descriptor),
        ))
    for name, original, replacement, target in WILD_SOURCES:
        descriptor = (r"\(L[^;]+;ILandroid/view/View;\)V"
                      if name == "Yc0" else re.escape(original))
        out.append((
            "%s (any owner)" % name,
            re.compile(r"invoke-(?:virtual|interface)(/range)? (\{[^}]*\}), L[^;]+;->%s%s"
                       % (name, descriptor)),
            r"invoke-static\1 \2, %s->%s%s" % (target, name, replacement),
        ))
    for owner, field, kind, name, target in FIELD_SOURCES:
        # iget-object vA, vB, Owner->field:Type
        #   -> invoke-static {vB}, Ours->name(Owner)Type ; move-result-object vA
        #
        # Which iget it is depends on what is being read: a long or a double is
        # two registers wide and has instructions of its own, and reading one
        # with the wrong instruction is not something the assembler forgives.
        if kind in ("J", "D"):
            read, took = "iget-wide", "move-result-wide"
        elif kind in ("Z", "B", "S", "C", "I", "F"):
            read, took = "iget", "move-result"
        else:
            read, took = "iget-object", "move-result-object"
        out.append((
            "%s.%s" % (owner.rsplit("/", 1)[-1][:-1], field),
            re.compile(r"^(\s*)%s ([vp]\d+), ([vp]\d+), %s->%s:%s$"
                       % (read, re.escape(owner), re.escape(field), re.escape(kind)),
                       re.MULTILINE),
            r"\1invoke-static {\3}, %s->%s(%s)%s\n\n\1%s \2"
            % (target, name, owner, kind, took),
        ))
    return out


def rewrite_models(root: str) -> Dict[str, int]:
    """Rewrite every call on TikTok's models, counting them by signature."""
    counts: Dict[str, int] = {}
    prepared = model_rules()
    owners = tuple(set([owner for owner, _n, _o, _r, _t in MODEL_SOURCES]
                       + [owner for owner, _n, _o, _r, _t in MODEL_STATICS]
                       + [owner for owner, _f, _k, _n, _t in FIELD_SOURCES]
                       # a rule with no owner of its own is recognised by the
                       # method it is looking for, or its file is never opened
                       + [name for name, _o, _r, _t in WILD_SOURCES]
                       # and one whose owner was found rather than written down
                       # is recognised by the owner that was found
                       + [FOUND[label][0]
                          for label, _d, _o, _t in DISCOVERED_STATICS + DISCOVERED_VIRTUALS
                          if label in FOUND]))
    owners += tuple(set(FOUND[label][0] for label, _n, _d, _o, _t in DATE_STATICS
                        if label in FOUND))
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".smali"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            if not any(owner in text for owner in owners):
                continue
            before = text
            for label, pattern, target in prepared:
                text, hits = pattern.subn(target, text)
                if hits:
                    counts[label] = counts.get(label, 0) + hits
            if text != before:
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write(text)
    return counts


def anchored_rules() -> List[Tuple[str, str, "re.Pattern[str]", str]]:
    """Rules and the marker the class they belong in has to carry."""
    out = []
    for anchor, owner, name, original, replacement, target in ANCHORED_SOURCES:
        out.append((
            anchor,
            "%s->%s (in the class marked %s)" % (owner.rsplit("/", 1)[-1][:-1], name, anchor),
            re.compile(r"invoke-virtual(/range)? (\{[^}]*\}), %s->%s%s"
                       % (re.escape(owner), name, re.escape(original))),
            r"invoke-static\1 \2, %s->%s%s" % (target, name, replacement),
        ))
    return out


def rewrite_anchored(root: str) -> Dict[str, int]:
    """Rewrite calls inside the one class that carries the marker."""
    counts: Dict[str, int] = {}
    prepared = anchored_rules()
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".smali"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            before = text
            for anchor, label, pattern, target in prepared:
                if anchor not in text:
                    continue
                text, hits = pattern.subn(target, text)
                if hits:
                    counts[label] = counts.get(label, 0) + hits
            if text != before:
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write(text)
    return counts


def carries_an_anchor(dex: bytes) -> bool:
    return any(anchor.encode() in dex for anchor, *_rest in ANCHORED_SOURCES)


def touches_a_model(dex: bytes) -> bool:
    """Whether a dex names one of TikTok's models and something we want on it."""
    for owner, name, _original, _replacement, _target in MODEL_SOURCES + MODEL_STATICS:
        theirs = name[0] if isinstance(name, tuple) else name
        if owner.encode() in dex and theirs.encode() in dex:
            return True
    for owner, field, _kind, _name, _target in FIELD_SOURCES:
        if owner.encode() in dex and field.encode() in dex:
            return True
    for name, _original, _replacement, _target in WILD_SOURCES:
        if name.encode() in dex:
            return True
    for label, _descriptor, _ours, _target in DISCOVERED_STATICS + DISCOVERED_VIRTUALS:
        found = FOUND.get(label)
        if found and found[0].encode() in dex and found[1].encode() in dex:
            return True
    for label, _expected, _descriptor, _ours, _target in DATE_STATICS:
        found = FOUND.get(label)
        if found and found[0].encode() in dex and found[1].encode() in dex:
            return True
    return False


def reads_a_colour(dex: bytes) -> bool:
    """Whether a dex asks the framework for a colour.

    Most of TikTok's pink is not a constant at all: it is a colour resource,
    fetched by id, from code spread across most of the apk. Reaching it means
    opening every dex that asks -- which is most of them, and the reason a
    build takes a quarter of an hour rather than two minutes.
    """
    for owner, name, _original, _replacement in COLOUR_SOURCES:
        if owner.encode() in dex and name.encode() in dex:
            return True
    for owner, name, _signature in COLOUR_STATICS:
        if owner.encode() in dex and name.encode() in dex:
            return True
    return False


def holds_the_pink(dex: bytes) -> bool:
    """Whether a dex has the pink as a constant in an instruction.

    The four bytes of the colour turn up in string data and in tables too, and
    taking a dex apart costs half a minute -- so this looks for the instruction
    itself: opcode 0x14, `const vAA, #+BBBBBBBB`, the register, then the value.
    """
    needle = struct.pack("<I", TIKTOK_PINK)
    at = dex.find(needle)
    while at != -1:
        if at >= 2 and dex[at - 2] == 0x14:
            return True
        at = dex.find(needle, at + 1)
    return False


def rewrite_accent(root: str) -> Dict[str, int]:
    """Rewrite everywhere the pink is written down, counting as it goes."""
    counts: Dict[str, int] = {}
    prepared = accent_rules()
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".smali"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            before = text
            for label, pattern, target in prepared:
                text, hits = pattern.subn(target, text)
                if hits:
                    counts[label] = counts.get(label, 0) + hits
            if text != before:
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write(text)
    return counts


def force_false(root: str) -> Dict[str, int]:
    """Rewrite the methods in FORCED_FALSE to `return false`, body and all."""
    counts: Dict[str, int] = {}
    for class_name, signature in FORCED_FALSE:
        path = os.path.join(root, *class_name.split("/")) + ".smali"
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        pattern = re.compile(
            r"^\.method ([^\n]*%s)\n.*?^\.end method$" % re.escape(signature),
            re.MULTILINE | re.DOTALL,
        )
        match = pattern.search(text)
        if match is None:
            raise RuntimeError(
                "%s is in the apk but has no %s to rewrite -- the fallback this "
                "depends on has moved, and Google sign-in would be dead on arrival"
                % (class_name, signature)
            )
        stub = ".method %s\n    .registers 1\n\n    const/4 v0, 0x0\n\n    return v0\n.end method" % (
            match.group(1),
        )
        text = text[: match.start()] + stub + text[match.end():]
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        counts["%s->%s" % (class_name.rsplit("/", 1)[-1], signature)] = 1
    return counts


def interesting(dex: bytes, literals: Optional[Dict[str, str]] = None) -> bool:
    """A quick look at the raw dex before spending a minute on it.

    Every method a dex calls and every string it holds is in its string table,
    so a dex that never spells `TelephonyManager` cannot be calling one of
    these, and one that never spells an authority cannot be looking it up.
    """
    for old in (literals or {}):
        if old.encode() in dex:
            return True
    if (holds_the_pink(dex) or reads_a_colour(dex) or touches_a_model(dex)
            or carries_an_anchor(dex)):
        return True
    for class_name, _signature in FORCED_FALSE:
        if ("L%s;" % class_name).encode() in dex:
            return True
    if TELEPHONY.encode() not in dex:
        return False
    return any(name.encode() in dex for name, _o, _r in TARGETS)


def rewrite_literals(root: str, literals: Dict[str, str]) -> Dict[str, int]:
    """Swap whole string constants, for the authorities the manifest renamed.

    A provider authority renamed in the manifest and not in the code is an app
    that cannot find its own provider -- so if any of these strings turn out to
    be in the bytecode after all, they move with it.
    """
    counts: Dict[str, int] = {}
    if not literals:
        return counts
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".smali"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            before = text
            for old, new in literals.items():
                needle = '"%s"' % old
                hits = text.count(needle)
                if hits:
                    text = text.replace(needle, '"%s"' % new)
                    counts[old] = counts.get(old, 0) + hits
            if text != before:
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write(text)
    return counts


def rewrite_smali(root: str) -> Dict[str, int]:
    """Rewrite every call site under `root`, counting them by signature."""
    counts: Dict[str, int] = {}
    prepared = rules()
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".smali"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            if TELEPHONY not in text:
                continue
            before = text
            for label, pattern, target in prepared:
                text, hits = pattern.subn(target, text)
                if hits:
                    counts[label] = counts.get(label, 0) + hits
            if text != before:
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write(text)
    return counts


class Smali:
    """baksmali and smali, from the one jar the build downloads."""

    def __init__(self, jar: str, api: int, jobs: int = 0, heap: str = "4g"):
        self.jar = jar
        self.api = api
        self.jobs = jobs or (os.cpu_count() or 2)
        self.heap = heap

    def _run(self, main: str, args: List[str]) -> None:
        command = ["java", "-Xmx" + self.heap, "-cp", self.jar, main] + args
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode != 0:
            raise RuntimeError(
                "%s failed:\n%s\n%s" % (main.split(".")[-2], result.stdout, result.stderr)
            )

    def disassemble(self, dex_path: str, out_dir: str) -> None:
        self._run(
            "com.android.tools.smali.baksmali.Main",
            ["d", "-a", str(self.api), "-j", str(self.jobs), "-o", out_dir, dex_path],
        )

    def assemble(self, smali_dir: str, dex_path: str) -> None:
        self._run(
            "com.android.tools.smali.smali.Main",
            ["a", "-a", str(self.api), "-j", str(self.jobs), "-o", dex_path, smali_dir],
        )


def dex_format(dex: bytes) -> str:
    """The three digits after `dex\n`: 035, 038, 039 ..."""
    return dex[4:7].decode("ascii", "replace")


# --------------------------------------------------------- the landing sites
#
# Every rewrite above turns a call into the app's own code into a call into
# ours, and smali will assemble a call to a method that does not exist without
# a word: a dex may reference anything, and the runtime only goes looking when
# the instruction is reached. So a missing method is not a build failure, it is
# a NoSuchMethodError on whichever screen first draws that colour -- which is
# how `Accent.getColor(Context, int)`, rewritten at 33 dex files' worth of call
# sites and never written in Java, shipped once.


def rewrite_targets() -> List[str]:
    """Every static the rewrites point at, as `Lowner;->name(descriptor)`."""
    out = ["%s->accent()I" % ACCENT]
    for name, _original, replacement in TARGETS:
        out.append("%s->%s%s" % (REGION, name, replacement))
    for _owner, name, _original, replacement in COLOUR_SOURCES:
        out.append("%s->%s%s" % (ACCENT, name, replacement))
    for _owner, name, signature in COLOUR_STATICS:
        out.append("%s->%s%s" % (ACCENT, name, signature))
    for _owner, name, _original, replacement, target in MODEL_SOURCES + MODEL_STATICS:
        _theirs, ours = name if isinstance(name, tuple) else (name, name)
        out.append("%s->%s%s" % (target, ours, replacement))
    for label, _expected, descriptor, ours, target in DATE_STATICS:
        if label in FOUND:
            out.append("%s->%s%s" % (target, ours, descriptor))
    for owner, _field, kind, name, target in FIELD_SOURCES:
        out.append("%s->%s(%s)%s" % (target, name, owner, kind))
    for _anchor, _owner, name, _original, replacement, target in ANCHORED_SOURCES:
        out.append("%s->%s%s" % (target, name, replacement))
    for name, _original, replacement, target in WILD_SOURCES:
        out.append("%s->%s%s" % (target, name, replacement))
    for label, descriptor, ours, target in DISCOVERED_STATICS:
        if label in FOUND:
            out.append("%s->%s%s" % (target, ours, descriptor))
    for label, descriptor, ours, target in DISCOVERED_VIRTUALS:
        if label in FOUND:
            out.append("%s->%s%s"
                       % (target, ours, "(Ljava/lang/Object;" + descriptor[1:]))
    return out


def _uleb(data: bytes, at: int) -> Tuple[int, int]:
    value = shift = 0
    while True:
        byte = data[at]
        at += 1
        value |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            return value, at


def defined_methods(dex: bytes) -> set:
    """The methods a dex actually defines, as `Lowner;->name(descriptor)`.

    Referenced methods are not enough: the whole point of the check is that a
    reference to a method nobody wrote is exactly what the build has to catch.
    So this walks the class definitions rather than the method table.
    """
    string_ids_off = struct.unpack_from("<I", dex, 60)[0]
    type_ids_off = struct.unpack_from("<I", dex, 68)[0]
    proto_ids_off = struct.unpack_from("<I", dex, 76)[0]
    method_ids_off = struct.unpack_from("<I", dex, 92)[0]
    class_defs_size, class_defs_off = struct.unpack_from("<2I", dex, 96)

    def string(index: int) -> str:
        at = struct.unpack_from("<I", dex, string_ids_off + 4 * index)[0]
        length, at = _uleb(dex, at)
        return dex[at:at + length].decode("utf-8", "replace")

    def type_name(index: int) -> str:
        return string(struct.unpack_from("<I", dex, type_ids_off + 4 * index)[0])

    def descriptor(index: int) -> str:
        _shorty, return_type, parameters = struct.unpack_from(
            "<3I", dex, proto_ids_off + 12 * index)
        arguments = ""
        if parameters:
            count = struct.unpack_from("<I", dex, parameters)[0]
            arguments = "".join(
                type_name(struct.unpack_from("<H", dex, parameters + 4 + 2 * i)[0])
                for i in range(count)
            )
        return "(%s)%s" % (arguments, type_name(return_type))

    def signature(index: int) -> str:
        owner, proto, name = struct.unpack_from("<HHI", dex, method_ids_off + 8 * index)
        return "%s->%s%s" % (type_name(owner), string(name), descriptor(proto))

    out = set()
    for i in range(class_defs_size):
        data_off = struct.unpack_from("<I", dex, class_defs_off + 32 * i + 24)[0]
        if not data_off:
            continue
        counts = []
        at = data_off
        for _ in range(4):  # static fields, instance fields, direct, virtual
            value, at = _uleb(dex, at)
            counts.append(value)
        for _ in range(counts[0] + counts[1]):
            _diff, at = _uleb(dex, at)
            _flags, at = _uleb(dex, at)
        for methods in (counts[2], counts[3]):
            index = 0
            for step in range(methods):
                diff, at = _uleb(dex, at)
                _flags, at = _uleb(dex, at)
                _code, at = _uleb(dex, at)
                index = diff if step == 0 else index + diff
                out.add(signature(index))
    return out


def find_statics(dexes: Dict[str, bytes]) -> Dict[str, Tuple[str, str]]:
    """Find each wanted static by its signature, across the whole apk.

    A descriptor is matched, not a name, so what comes back is whatever the
    obfuscator called it this release. Anything that matches in more than one
    class is dropped rather than guessed at: a rule that lands in the wrong
    place is worse than one that does not land at all.

    The date gates are the exception, and the reason is their names. `LIZ`,
    `LIZIZ` and the rest are what the obfuscator calls *any* method of that
    shape, in hundreds of classes, so asking for the one class that has a
    method called `LIZ` never gets a single answer. What is rare is a class
    that has all five -- so that is what is looked for (`pick_date_gates`).
    """
    wanted = {descriptor: label for label, descriptor, _ours, _target
              in DISCOVERED_STATICS + DISCOVERED_VIRTUALS}
    seen: Dict[str, set] = {label: set() for label in wanted.values()}
    date_wanted = {(name, descriptor): label
                   for label, name, descriptor, _ours, _target in DATE_STATICS}
    gates: Dict[str, set] = {label: set() for label in date_wanted.values()}

    for dex in dexes.values():
        for owner, name, descriptor in _static_methods(dex):
            label = wanted.get(descriptor)
            if label is not None:
                seen[label].add((owner, name))
            label = date_wanted.get((name, descriptor))
            if label is not None:
                gates[label].add(owner)

    out: Dict[str, Tuple[str, str]] = {}
    for label, found in seen.items():
        if len(found) == 1:
            out[label] = next(iter(found))
    out.update(pick_date_gates(gates, dexes))
    return out


def pick_date_gates(gates: Dict[str, set],
                    dexes: Optional[Dict[str, bytes]] = None) -> Dict[str, Tuple[str, str]]:
    """Which class holds the five date gates, from who carries each of them.

    `gates` maps each gate's label to every class that has a method of its
    name and shape. The answer is the class in all five sets. When more than
    one is, the ones that really define all five as statics win; if that is
    still not one class, the class the feature was written against breaks the
    tie. Anything else is "not found", and the build says why.
    """
    del DATE_REPORT[:]
    labels = [label for label, _name, _descriptor, _ours, _target in DATE_STATICS]
    for label in labels:
        DATE_REPORT.append("%s: %d classes have a method of that name and shape"
                           % (label, len(gates.get(label, ()))))

    common: Optional[set] = None
    for label in labels:
        here = set(gates.get(label, ()))
        common = here if common is None else common & here
    common = common or set()
    DATE_REPORT.append("%d of them have all five" % len(common))

    if len(common) > 1 and dexes:
        try:
            defining = _owners_defining_gates(dexes, common)
        except Exception as error:  # a dex this cannot read must not stop a build
            defining = set()
            DATE_REPORT.append("could not read the class definitions: %s" % error)
        DATE_REPORT.append("%d of those define all five themselves, as statics"
                           % len(defining))
        if defining:
            common = defining
    if len(common) > 1 and DATE_OWNER_HINT in common:
        common = {DATE_OWNER_HINT}
        DATE_REPORT.append("still several: the class this was written against wins")

    if len(common) == 1:
        owner = next(iter(common))
        DATE_REPORT.append("the gates are in %s" % owner)
        return {label: (owner, name)
                for label, name, _descriptor, _ours, _target in DATE_STATICS}

    if not common:
        # no class has all five: the old rule, each one alone, as long as
        # every one of them is unique on its own
        single = {label: next(iter(owners)) for label, owners in gates.items()
                  if len(owners) == 1}
        if len(single) == len(labels):
            DATE_REPORT.append("no class has all five, but each is unique alone")
            return {label: (single[label], name)
                    for label, name, _descriptor, _ours, _target in DATE_STATICS}

    DATE_REPORT.append("cannot tell which class holds the gates%s"
                       % ((": " + ", ".join(sorted(common)[:6])) if common else ""))
    return {}


def _owners_defining_gates(dexes: Dict[str, bytes], owners: set) -> set:
    """The owners, out of `owners`, that define all five gates as statics."""
    wanted = {(name, descriptor)
              for _label, name, descriptor, _ours, _target in DATE_STATICS}
    out = set()
    for dex in dexes.values():
        for owner, defined in _static_definitions(dex, owners):
            if wanted <= defined:
                out.add(owner)
    return out


def _static_definitions(dex: bytes, owners: set):
    """For each of `owners` defined in this dex: its static methods.

    Yields (owner, {(name, descriptor), ...}). Only the classes asked about
    have their method lists read; the rest are passed over by name.
    """
    if not any(owner.encode() in dex for owner in owners):
        return
    string_ids_off = struct.unpack_from("<I", dex, 60)[0]
    type_ids_off = struct.unpack_from("<I", dex, 68)[0]
    proto_ids_off = struct.unpack_from("<I", dex, 76)[0]
    method_ids_off = struct.unpack_from("<I", dex, 92)[0]
    class_defs_size, class_defs_off = struct.unpack_from("<2I", dex, 96)

    def string(index: int) -> str:
        at = struct.unpack_from("<I", dex, string_ids_off + 4 * index)[0]
        length, at = _uleb(dex, at)
        return dex[at:at + length].decode("utf-8", "replace")

    def type_name(index: int) -> str:
        return string(struct.unpack_from("<I", dex, type_ids_off + 4 * index)[0])

    def descriptor(index: int) -> str:
        _shorty, return_type, parameters = struct.unpack_from(
            "<3I", dex, proto_ids_off + 12 * index)
        arguments = ""
        if parameters:
            count = struct.unpack_from("<I", dex, parameters)[0]
            arguments = "".join(
                type_name(struct.unpack_from("<H", dex, parameters + 4 + 2 * i)[0])
                for i in range(count))
        return "(%s)%s" % (arguments, type_name(return_type))

    for i in range(class_defs_size):
        class_idx = struct.unpack_from("<I", dex, class_defs_off + 32 * i)[0]
        owner = type_name(class_idx)
        if owner not in owners:
            continue
        data_off = struct.unpack_from("<I", dex, class_defs_off + 32 * i + 24)[0]
        if not data_off:
            continue
        counts = []
        at = data_off
        for _ in range(4):  # static fields, instance fields, direct, virtual
            value, at = _uleb(dex, at)
            counts.append(value)
        for _ in range(counts[0] + counts[1]):
            _diff, at = _uleb(dex, at)
            _flags, at = _uleb(dex, at)
        defined = set()
        index = 0
        for step in range(counts[2]):  # statics are always direct methods
            diff, at = _uleb(dex, at)
            flags, at = _uleb(dex, at)
            _code, at = _uleb(dex, at)
            index = diff if step == 0 else index + diff
            if flags & 0x0008:  # ACC_STATIC
                _owner, proto, name = struct.unpack_from(
                    "<HHI", dex, method_ids_off + 8 * index)
                defined.add((string(name), descriptor(proto)))
        yield owner, defined


def _static_methods(dex: bytes):
    """Every method the dex names, as (owner, name, descriptor)."""
    strings = struct.unpack_from("<I", dex, 60)[0]
    types = struct.unpack_from("<I", dex, 68)[0]
    protos = struct.unpack_from("<I", dex, 76)[0]
    count, methods = struct.unpack_from("<2I", dex, 88)

    def text(index: int) -> str:
        at = struct.unpack_from("<I", dex, strings + 4 * index)[0]
        size, at = _uleb(dex, at)
        return dex[at:at + size].decode("utf-8", "replace")

    def kind(index: int) -> str:
        return text(struct.unpack_from("<I", dex, types + 4 * index)[0])

    def shape(index: int) -> str:
        _shorty, ret, params = struct.unpack_from("<3I", dex, protos + 12 * index)
        taken = ""
        if params:
            how_many = struct.unpack_from("<I", dex, params)[0]
            taken = "".join(kind(struct.unpack_from("<H", dex, params + 4 + 2 * i)[0])
                            for i in range(how_many))
        return "(%s)%s" % (taken, kind(ret))

    for i in range(count):
        owner, proto, name = struct.unpack_from("<HHI", dex, methods + 8 * i)
        yield kind(owner), text(name), shape(proto)


def missing_targets(dex: bytes) -> List[str]:
    """Which of the rewrites' landing sites the mod's own dex does not define."""
    defined = defined_methods(dex)
    return [target for target in rewrite_targets() if target not in defined]


def patch(dex: bytes, name: str, smali: Smali, workspace: str,
          literals: Optional[Dict[str, str]] = None) -> Tuple[bytes, Dict[str, int]]:
    """Take one dex apart, rewrite what is in it, put it back together."""
    # a room of its own per dex, and never under a name something else uses:
    # the mod's own dex is called classes.dex too
    room = os.path.join(workspace, name.replace(".dex", ""))
    shutil.rmtree(room, ignore_errors=True)
    os.makedirs(room, exist_ok=True)

    dex_in = os.path.join(room, "in.dex")
    dex_out = os.path.join(room, "out.dex")
    with open(dex_in, "wb") as handle:
        handle.write(dex)

    smali.disassemble(dex_in, os.path.join(room, "smali"))
    counts = rewrite_smali(os.path.join(room, "smali"))
    counts.update(rewrite_literals(os.path.join(room, "smali"), literals or {}))
    counts.update(force_false(os.path.join(room, "smali")))
    counts.update(rewrite_accent(os.path.join(room, "smali")))
    counts.update(rewrite_models(os.path.join(room, "smali")))
    counts.update(rewrite_anchored(os.path.join(room, "smali")))
    if not counts:
        shutil.rmtree(room, ignore_errors=True)
        return dex, counts

    smali.assemble(os.path.join(room, "smali"), dex_out)
    with open(dex_out, "rb") as handle:
        patched = handle.read()
    shutil.rmtree(room, ignore_errors=True)

    if dex_format(patched) != dex_format(dex):
        raise RuntimeError(
            "%s came back as dex %s, it went in as dex %s -- an Android old "
            "enough to be in the apk's minSdk would refuse to load it"
            % (name, dex_format(patched), dex_format(dex))
        )
    return patched, counts


def next_dex_name(names: List[str]) -> str:
    """The name a new dex has to take to be loaded: the next in the run.

    The runtime loads classes.dex, then classes2.dex, and stops at the first
    number that is missing -- so an extra dex is only read if it continues the
    sequence.
    """
    used = set()
    for name in names:
        match = re.fullmatch(r"classes(\d*)\.dex", name)
        if match:
            used.add(int(match.group(1) or "1"))
    number = 2
    while number in used:
        number += 1
    return "classes%d.dex" % number
