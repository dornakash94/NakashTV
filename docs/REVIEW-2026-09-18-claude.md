# סקירת קוד — Claude, 18.9.2026 (אחרי ההעברה מ־Codex)

מה נבדק: מבנה הפרויקט, CLAUDE.md, network_security_config, חתימה ו־R8, נתיבי ניווט, PlaybackPolicy, LoginScreen, TvSmokeRunner, חיפוש הדפסות של URL/סיסמאות בלוגים.

## ממצא מהותי אחד
`app/proguard-rules.pro` היה **ריק** בזמן ש־`isMinifyEnabled = true`. kotlinx.serialization ו־Retrofit suspend הם הסיבה הקלאסית ל־Release שמתקמפל אבל קורס בזמן ריצה (`SerializationException: Serializer for class X is not found`, או `Unable to create call adapter for Continuation`). זה מתאים בדיוק לפריט הפתוח בהעברה: "בדיקת התקנה נקייה של Release עדיין לא הסתיימה". נוספו כללים (ראה הקובץ). **חובה לבנות Release מחדש ולבדוק login + טעינת קטלוג לפני הפצה.**

## נבדק ותקין
- אין הדפסות של URL/סיסמה ל־logcat.
- `network_security_config`: HTTP רק ליעדים שאושרו במפורש, בלי פתיחה גלובלית.
- הסמוק־טסט מסרב לרוץ מול חשבון אמיתי (בדיקת `username == "fixture"`). טוב.
- מסך הכניסה ללא ערכי ברירת מחדל של ספק.
- PlaybackPolicy מגביל seek בארכיון לנקודות שכבר שודרו.

## הערות לא־חוסמות
1. `docs/` לא נכלל בזיפ ההעברה (spec.md, PROGRESS.md, TV-TESTING.md) — לצרף בהעברה הבאה.
2. IMDb לא מומש; הדירוג הוא של הספק — לוודא שה־UI לא כותב "IMDb" ליד המספר (grep מהיר לא מצא, אבל לבדוק בתמונות מסך).
3. אין Git. לפני שינוי נוסף: `git init` + commit של המצב הזה. גם המפתח ב־work/signing צריך גיבוי פרטי.
4. הפצה: מכאן אין לי גישה ל־Sites/Mac. חלופה פשוטה שאתה שולט בה: GitHub Release פרטי→ציבורי לקובץ `.apk` בלבד (בלי המקור אם לא רוצים), או Cloudflare R2/Backblaze עם URL יציב `/NakashTV.apk`. Downloader מסתדר עם כל אחד מהם.
