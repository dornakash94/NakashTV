# שינויים לגרסה 0.2 — Claude, 19.9.2026 (לא קומפל כאן; לבנות ולבדוק באמולטור)

מיושם בקוד, לפי שש ההערות של דור. קבצים שנגעתי בהם: `ui/player/PlayerScreen.kt`, `player/PlayerController.kt`,
`data/local/PlaybackPreferences.kt`, `ui/nav/NakashNavHost.kt`, `ui/home/HomeViewModel.kt`, `ui/home/HomeScreen.kt`,
`ui/home/OtherScreens.kt`, `app/proguard-rules.pro`.

| # | בקשה | מה נעשה | איך בודקים |
|---|---|---|---|
| 1 | שומר מסך קופץ אחרי 10 דקות בסרט | `PlayerView.keepScreenOn = true` בנגן | סרט 15 דק׳ בלי לגעת בשלט — אין שומר מסך; ביציאה מהנגן השומר חוזר לפעול |
| 2 | סיידבר לא מובן שהוא פתוח | סרגל 64dp שקוף עם אייקונים (הפעיל בהיר, השאר עמומים). כשהפוקוס עליו: פאנל כהה 300dp עם שמות **וכל התוכן מאחוריו מתעמעם** (scrim 55%) | פוקוס על הסרגל → התוכן מתעמעם; יוצאים → חוזר |
| 3 | מועדפים בנוחות + שורות בבית במקום "הרשימה שלי" בסיידבר | "הרשימה שלי" הוסר מהסיידבר. בבית: שורת **"הערוצים שלי"** (המועדפים; אם אין — ישראל) ושורת **"הרשימה שלי"** (סרטים+סדרות שסומנו). הוספה: לחיצה ארוכה על כרטיס ערוץ בבית/בערוצים, **▶ בזמן שידור חי בנגן**, כפתור ♥ בפס הנגן, או כפתור בשלט שהוקצה ל"מועדפים". Toast מאשר | לחיצה ארוכה על ערוץ → Toast → מופיע בשורת "הערוצים שלי" |
| 4 | Back מעמוד ראשי → הסמן לנאב־בר על הסקשן הנוכחי | `BackHandler`: במסך ראשי Back מעביר פוקוס לפריט הפעיל בסרגל; Back מהסרגל → בית; Back מהסרגל בבית → דיאלוג יציאה. מסכי פרטים/נגן ללא שינוי | סרטים → Back → הפוקוס על "סרטים" בסרגל → Back → בית → Back → "לצאת?" |
| 5 | הגדרות בסטייל נטפליקס + מיפוי כפתורים אמיתיים | מסך בשני טורים (כללי / שלט / נגן / חשבון), שורות שקטות עם ערך. **"הוסף כפתור": לוחצים על כפתור פיזי בשלט**, האפליקציה תופסת את הקוד ומציגה את שמו (למשל "Prog Red", "Captions"), ובוחרים פעולה: ניגון/השהיה, כתוביות, יחס תמונה, מועדפים, החלפת מקור, לוח מקוצר, מההתחלה, תפריט, הצגה/הסתרת פקדים. חצים/OK/חזרה/בית/ספרות שמורים. ברירות מחדל לכפתורים סטנדרטיים (Play/Pause, Captions, Guide, Bookmark, Menu) | הגדרות → שלט → הוסף כפתור → לחיצה על כפתור בשלט → בחירת פעולה → בנגן הכפתור עובד |
| 6 | פקדי הנגן מכוערים | הכפתורים המרובעים הוחלפו ב־**פס אייקונים עגולים שקופים** בסטייל נטפליקס: בפוקוס — דיסק לבן עם אייקון שחור ותווית קטנה מעליו. VOD: נגן/השהה · הפרק הבא (בסדרות) · כתוביות · יחס תמונה. Live: ♥ מועדפים · מקור (אם יש גיבויים) · מההתחלה (אם יש ארכיון) · כתוביות · יחס תמונה. חלוניות כתוביות/יחס תמונה כהות יותר ונקיות | ▼ בנגן → הפס; ▲ חוזר לווידאו |

## שינויי מבנה שבנאי צריך לדעת
- `PlaybackPreferences`: `buttons` הוסר; במקומו `bindings: List<RemoteBinding>` (כל keycode), `bind()/unbind()`, `RESERVED`, `DEFAULTS`, `keyName()`. `RemoteAction` קיבל ערכים חדשים.
- `PlayerViewModel` מקבל גם `UserRepository` (Hilt מזריק אוטומטית). `PlayerController.toast(msg)` נוסף.
- `Dest.MyList` נשאר כ־route ("mylist") אבל לא ברשימת הסרגל; `Dest.topLevel` נוסף.
- `HomeViewModel.rows`: `combine` של 5 זרמים בלבד → הזרמים הנוספים מקוננים.

## מה לא נגעתי
Catchup, DiscoverScreen, SeriesDetail, חיפוש, Guide. אין שינוי בסכימת Room. אין שינוי ב־UA / StreamUrls.

## אחרי הבנייה
1. `testDebugUnitTest` — הבדיקות הקיימות לא נוגעות בקוד ששונה חוץ מ־PlaybackPreferences (אם יש בדיקה עליו — לעדכן ל־`bindings`).
2. Release עם `proguard-rules.pro` החדש (היה ריק) — לבדוק login וטעינת קטלוג על התקנה נקייה.
3. versionCode 3, versionName 0.2.0-beta.1, אותו מפתח חתימה, ולעדכן את ה־Release ב־GitHub (קוד ה־Downloader 4033247 מצביע על v0.1.0 — צריך קוד חדש או להחליף את ה־asset תחת תג חדש).

## Netflix-grade UX pass (2026-09-19)

Navigation moved from the side rail to a top text-tab bar (search and settings as icons); the bar floats over the hero on home/movies/series with a top-down fade, and Back still climbs card → nav → home → exit prompt. "הרשימה שלי" is no longer a nav tab: it is a shelf on home (existing) and now also on movies/series, built from favorites.

Rows were rebuilt on a shared NetflixRow (foundation LazyRow + custom BringIntoViewSpec): the focused card is pulled to the row start with a 32dp gutter, mirrored correctly for RTL, replacing tv-foundation's center pivot. Entry focus is pinned to the first card of the first shelf (Home, Discover, Live, and category grids) instead of Compose's spatial guess, with a retry loop because lazy items attach late (fixes a FocusRequester crash). Category "הצג הכול" now opens a full-screen gallery (heading + count + poster grid) instead of a grid squeezed under the hero.

Cards: posters are 118x177 with a springy 1.1 focus pop and 2.5dp ring; home continue/channel cards grew to 200x112. Hero buttons are translucent until focused (solid white when focused). Screen switches crossfade. Live got a 280dp hero preview with live progress and an on-preview digits chip; the guide got a gold selected-channel marker, live-progress program rows, and pill buttons. Unit tests: 39/39.
