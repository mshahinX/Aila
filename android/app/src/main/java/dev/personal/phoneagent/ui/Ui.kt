package dev.personal.phoneagent.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import dev.personal.phoneagent.R

/**
 * Köməkçi v9 design system: one chat screen, simple and enterprise (canvas page "v9" of
 * https://claude.ai/artifact/Ep3Xv4czcvBdiMhi3MG8kW).
 *
 * - Colour tokens live in res/values(-night)/colors.xml and are loaded by [init]; never hard-code colours in screens.
 * - Onest (res/font, SIL OFL) in three weights; sizes are sp × [scale] on top of Android's own font size.
 * - White cards with 1dp borders on a light grey page, 12dp corners, no shadows or gradients.
 * - One navy primary action per view; orange only for "you do this / permission"; red only for Stop/failure.
 * - Touch targets ≥ 48dp (buttons 56–64dp); every icon-only control has a spoken label.
 */
object Ui {
    // ---------- Colour tokens (loaded from resources, light/dark) ----------
    var ground = 0; private set
    var surface = 0; private set
    var bubble = 0; private set
    var ink = 0; private set
    var muted = 0; private set
    var line = 0; private set
    var outline = 0; private set
    var accent = 0; private set
    var onAccent = 0; private set
    var accentDark = 0; private set
    var accentSoft = 0; private set
    var primaryContainer = 0; private set
    var onPrimaryContainer = 0; private set
    var strong = 0; private set
    var onStrong = 0; private set
    var inversePrimary = 0; private set
    var amber = 0; private set
    var amberSoft = 0; private set
    var amberLine = 0; private set
    var saffron = 0; private set
    var onSaffron = 0; private set
    var ok = 0; private set
    var okSoft = 0; private set
    var stop = 0; private set
    var onStop = 0; private set
    var stopSoft = 0; private set
    var scrim = 0; private set
    var night = false; private set

    // ---------- Type faces ----------
    var regular: Typeface = Typeface.DEFAULT; private set
    var semibold: Typeface = Typeface.DEFAULT_BOLD; private set
    var bold: Typeface = Typeface.DEFAULT_BOLD; private set

    /** In-app text size multiplier, chosen in Settings. */
    @Volatile var scale = 1f

    /** Loads the palette and fonts for the context's current day/night configuration. Call before building any view. */
    fun init(c: Context) {
        night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        fun k(id: Int) = c.getColor(id)
        ground = k(R.color.k_ground); surface = k(R.color.k_surface); bubble = k(R.color.k_surface_alt)
        ink = k(R.color.k_ink); muted = k(R.color.k_muted); line = k(R.color.k_line); outline = k(R.color.k_outline)
        accent = k(R.color.k_accent); onAccent = k(R.color.k_on_accent); accentDark = k(R.color.k_accent_text); accentSoft = k(R.color.k_accent_soft)
        primaryContainer = k(R.color.k_primary_container); onPrimaryContainer = k(R.color.k_on_primary_container)
        strong = k(R.color.k_strong); onStrong = k(R.color.k_on_strong); inversePrimary = k(R.color.k_inverse_primary)
        amber = k(R.color.k_you); amberSoft = k(R.color.k_you_soft); amberLine = k(R.color.k_you_line)
        saffron = k(R.color.k_saffron); onSaffron = k(R.color.k_on_saffron)
        ok = k(R.color.k_ok); okSoft = k(R.color.k_ok_soft); stop = k(R.color.k_stop); onStop = k(R.color.k_on_stop); stopSoft = k(R.color.k_stop_soft)
        scrim = k(R.color.k_scrim)
        runCatching {
            regular = c.resources.getFont(R.font.onest_regular)
            semibold = c.resources.getFont(R.font.onest_semibold)
            bold = c.resources.getFont(R.font.onest_bold)
        }
    }

    // ---------- Type scale (sp) ----------
    object Type { const val DISPLAY = 28f; const val TITLE = 24f; const val HEADLINE = 20f; const val BODY = 17f
        const val BODY_LARGE = 18f; const val LABEL = 15f; const val CAPTION = 14f }

    enum class Tone { INFO, YOU, OK, ERROR, NEUTRAL }
    fun fg(t: Tone) = when(t) { Tone.INFO -> accentDark; Tone.YOU -> amber; Tone.OK -> ok; Tone.ERROR -> stop; Tone.NEUTRAL -> muted }
    fun bg(t: Tone) = when(t) { Tone.INFO -> accentSoft; Tone.YOU -> amberSoft; Tone.OK -> okSoft; Tone.ERROR -> stopSoft; Tone.NEUTRAL -> bubble }
    private fun border(t: Tone) = when(t) { Tone.YOU -> amberLine; Tone.ERROR -> stop; else -> line }

    fun dp(c: Context, value: Int) = (value*c.resources.displayMetrics.density).toInt()
    private fun size(value: Float) = value*scale

    // ---------- Primitives ----------
    fun rounded(color: Int, radiusPx: Float = 24f, stroke: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius=radiusPx; if(stroke!=null) setStroke(strokePx,stroke)
    }
    /** Corner radii in dp: top-start, top-end, bottom-end, bottom-start (LTR). */
    fun corners(c: Context, color: Int, ts: Int, te: Int, be: Int, bs: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); val f={ v: Int -> dp(c,v).toFloat() }
        cornerRadii=floatArrayOf(f(ts),f(ts),f(te),f(te),f(be),f(be),f(bs),f(bs))
        if(stroke!=null) setStroke(maxOf(1,dp(c,1)),stroke)
    }
    private fun ripple() = ColorStateList.valueOf(if(night) Color.argb(50,255,255,255) else Color.argb(28,16,24,40))
    fun pressable(c: Context, color: Int, stroke: Int?, radiusDp: Int = 12, strokeDp: Float = 1.5f): Drawable {
        val shape=rounded(color,dp(c,radiusDp).toFloat(),stroke,maxOf(1,(strokeDp*c.resources.displayMetrics.density).toInt()))
        return RippleDrawable(ripple(),shape,rounded(Color.BLACK,dp(c,radiusDp).toFloat()))
    }
    private fun ghost(c: Context, radiusDp: Int = 12) = RippleDrawable(ripple(),null,rounded(Color.BLACK,dp(c,radiusDp).toFloat()))
    fun lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
           top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(width,height,weight).apply { topMargin=top; bottomMargin=bottom; marginStart=start; marginEnd=end }
    fun vertical(c: Context) = LinearLayout(c).apply { orientation=LinearLayout.VERTICAL }
    fun horizontal(c: Context, center: Boolean = true) = LinearLayout(c).apply { if(center) gravity=Gravity.CENTER_VERTICAL }
    fun space(c: Context, heightDp: Int) = View(c).apply { layoutParams=LinearLayout.LayoutParams(1,dp(c,heightDp)) }
    fun divider(c: Context, insetDp: Int = 0) = View(c).apply {
        setBackgroundColor(line); layoutParams=lp(height=maxOf(1,dp(c,1)),start=dp(c,insetDp)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---------- Edge-to-edge scaffold ----------
    /** Draws behind system bars and pads [root] by bar/IME/cutout insets, so content never sits under them (Android 15 enforces this). */
    fun edgeToEdge(a: Activity, root: View, padStartDp: Int = 0, padTopDp: Int = 0, padEndDp: Int = 0, padBottomDp: Int = 0) {
        a.window.setDecorFitsSystemWindows(false)
        a.window.insetsController?.setSystemBarsAppearance(
            if(night) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        val s=dp(a,padStartDp); val t=dp(a,padTopDp); val e=dp(a,padEndDp); val b=dp(a,padBottomDp)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime=insets.getInsets(WindowInsets.Type.ime())
            v.setPaddingRelative(s+bars.left,t+bars.top,e+bars.right,b+maxOf(bars.bottom,ime.bottom))
            insets
        }
        root.requestApplyInsets()
    }
    /** Standard screen: white top bar, scrolling grey body, optional white bottom dock. Returns (root, body). */
    fun scaffold(a: Activity, topBar: View?, dock: View? = null): Pair<LinearLayout,LinearLayout> {
        val root=vertical(a).apply { setBackgroundColor(surface) }
        topBar?.let { root.addView(it); root.addView(divider(a)) }
        val body=vertical(a).apply { setPadding(dp(a,16),dp(a,4),dp(a,16),dp(a,24)) }
        root.addView(ScrollView(a).apply { addView(body); isFillViewport=true; isVerticalScrollBarEnabled=false; clipToPadding=false; setBackgroundColor(ground) },lp(height=0,weight=1f))
        dock?.let {
            root.addView(divider(a))
            root.addView(vertical(a).apply { setPadding(dp(a,16),dp(a,12),dp(a,16),dp(a,12)); addView(it) })
        }
        a.setContentView(root)
        edgeToEdge(a,root)
        return root to body
    }

    // ---------- Text ----------
    fun title(c: Context, value: String, sizeSp: Float = Type.TITLE) = TextView(c).apply {
        text=value; textSize=size(sizeSp); setTextColor(ink); typeface=bold
        setLineSpacing(0f,1.12f); isFocusable=true; isAccessibilityHeading=true
        setPadding(0,0,0,dp(c,6))
    }
    fun text(c: Context, value: String, sizeSp: Float = Type.BODY_LARGE, color: Int = ink, bold: Boolean = false) = TextView(c).apply {
        text=value; textSize=size(sizeSp); setTextColor(color); setLineSpacing(0f,1.3f)
        typeface=if(bold) semibold else regular
        setPadding(0,dp(c,2),0,dp(c,2))
    }
    fun label(c: Context, value: String) = text(c,value,Type.LABEL,muted,bold=true)
    /** Group heading above a list group. */
    fun section(c: Context, value: String) = text(c,value,Type.LABEL,muted,bold=true).apply {
        isAccessibilityHeading=true; setPadding(dp(c,4),dp(c,20),dp(c,4),dp(c,8))
    }

    // ---------- Buttons ----------
    private fun baseButton(c: Context, value: String, heightDp: Int, click: () -> Unit) = Button(c).apply {
        text=value; isAllCaps=false; minHeight=dp(c,heightDp); minimumHeight=dp(c,heightDp); minWidth=0; minimumWidth=0
        stateListAnimator=null; setPadding(dp(c,18),0,dp(c,18),0); gravity=Gravity.CENTER
        maxLines=2; ellipsize=TextUtils.TruncateAt.END; typeface=semibold; letterSpacing=0f
        setOnClickListener { click() }
    }
    /** The one main action: navy, filled. */
    fun primary(c: Context, value: String, heightDp: Int = 56, click: () -> Unit) = baseButton(c,value,heightDp,click).apply {
        textSize=size(Type.BODY_LARGE); setTextColor(onAccent); background=pressable(c,accent,null)
    }
    /** Second choice next to a primary: white with a grey border. */
    fun secondary(c: Context, value: String, heightDp: Int = 56, click: () -> Unit) = baseButton(c,value,heightDp,click).apply {
        textSize=size(Type.BODY_LARGE); setTextColor(ink); background=pressable(c,surface,outline)
    }
    /** Low-emphasis text action (inside cards, rows). */
    fun tertiary(c: Context, value: String, color: Int = accentDark, click: () -> Unit) = baseButton(c,value,48,click).apply {
        textSize=size(Type.BODY); setTextColor(color); background=ghost(c,10); setPadding(dp(c,12),0,dp(c,12),0)
    }
    fun destructive(c: Context, value: String, heightDp: Int = 56, click: () -> Unit) = baseButton(c,value,heightDp,click).apply {
        textSize=size(Type.BODY_LARGE); setTextColor(onStop); background=pressable(c,stop,null)
    }
    /** Selectable option (time slots, answer choices). */
    fun choice(c: Context, value: String, selected: Boolean, sizeSp: Float = Type.BODY_LARGE, click: () -> Unit) = baseButton(c,value,56,click).apply {
        textSize=size(sizeSp)
        setTextColor(if(selected) accentDark else ink)
        background=pressable(c,if(selected) accentSoft else surface,if(selected) accent else outline)
        isSelected=selected
        stateDescription=if(selected) c.getString(R.string.state_selected) else c.getString(R.string.state_not_selected)
    }
    /** Red Stop with a square icon. Always labelled in words. */
    fun stopButton(c: Context, value: String = c.getString(R.string.stop), click: () -> Unit) = baseButton(c,value,56,click).apply {
        textSize=size(Type.BODY_LARGE); setTextColor(onStop); background=pressable(c,stop,null)
        val sq=c.getDrawable(R.drawable.ic_stop)?.mutate()?.apply { setTint(onStop); setBounds(0,0,dp(c,18),dp(c,18)) }
        setCompoundDrawablesRelative(sq,null,null,null); compoundDrawablePadding=dp(c,8); setPadding(dp(c,22),0,dp(c,22),0)
        contentDescription=c.getString(R.string.stop_spoken)
    }
    fun icon(c: Context, res: Int, tint: Int = accent, sizeDp: Int = 24) = ImageView(c).apply {
        setImageResource(res); imageTintList=ColorStateList.valueOf(tint); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams=LinearLayout.LayoutParams(dp(c,sizeDp),dp(c,sizeDp))
    }
    /** Square icon button. Ghost by default; always has a spoken label. */
    fun iconButton(c: Context, res: Int, spoken: String, filled: Boolean = false, sizeDp: Int = 48, outlined: Boolean = false, click: () -> Unit) = ImageButton(c).apply {
        setImageResource(res); imageTintList=ColorStateList.valueOf(if(filled) onAccent else muted)
        background=when { filled -> pressable(c,accent,null,16); outlined -> pressable(c,surface,outline,12); else -> ghost(c) }
        contentDescription=spoken; tooltipText=spoken
        val p=dp(c,if(sizeDp>=60) 16 else 12); setPadding(p,p,p,p); scaleType=ImageView.ScaleType.FIT_CENTER
        layoutParams=LinearLayout.LayoutParams(dp(c,sizeDp),dp(c,sizeDp))
        setOnClickListener { click() }
    }
    /** The voice button: 64dp navy square with a microphone, next to the text field. */
    fun voice(c: Context, value: String = c.getString(R.string.voice), click: () -> Unit) =
        iconButton(c,R.drawable.ic_mic,c.getString(R.string.voice_spoken),filled=true,sizeDp=64,click=click).apply { tooltipText=value }

    // ---------- Containers ----------
    fun card(c: Context, stroke: Int? = line, fill: Int = surface) = LinearLayout(c).apply {
        orientation=LinearLayout.VERTICAL; background=rounded(fill,dp(c,12).toFloat(),stroke,maxOf(1,dp(c,1)))
        setPadding(dp(c,16),dp(c,14),dp(c,16),dp(c,14))
    }
    /** Rounded list group with a border; children separated by inset hairlines. */
    fun group(c: Context) = LinearLayout(c).apply {
        orientation=LinearLayout.VERTICAL; background=rounded(surface,dp(c,12).toFloat(),line,maxOf(1,dp(c,1)))
        showDividers=LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable=InsetDrawable(GradientDrawable().apply { setColor(line); setSize(1,maxOf(1,dp(c,1))) },dp(c,16),0,0,0)
        clipToOutline=true
    }
    /** Pressable row for use inside [group]. */
    fun row(c: Context, click: (() -> Unit)?) = LinearLayout(c).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        minimumHeight=dp(c,64); setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12))
        if(click!=null) { isClickable=true; isFocusable=true
            background=RippleDrawable(ripple(),null,ColorDrawable(Color.BLACK))
            setOnClickListener { click() } }
    }
    /** Standard list row: optional leading icon, title, optional subtitle, optional trailing view. */
    fun listRow(c: Context, title: String, subtitle: String? = null, leading: Int? = null, leadingTint: Int = accent,
                trailing: View? = null, chevron: Boolean = false, click: (() -> Unit)? = null) = row(c,click).apply {
        if(leading!=null) addView(FrameLayout(c).apply {
            background=rounded(accentSoft,dp(c,10).toFloat()); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(icon(c,leading,leadingTint,22),FrameLayout.LayoutParams(dp(c,22),dp(c,22),Gravity.CENTER))
        },lp(dp(c,40),dp(c,40),end=dp(c,14)))
        val col=vertical(c)
        col.addView(text(c,title,Type.BODY,bold=true).apply { setPadding(0,0,0,0) })
        if(!subtitle.isNullOrBlank()) col.addView(text(c,subtitle,Type.LABEL,muted).apply { setPadding(0,dp(c,2),0,0) })
        addView(col,lp(0,weight=1f))
        if(trailing!=null) addView(trailing,lp(-2,start=dp(c,10)))
        if(chevron) addView(icon(c,R.drawable.ic_chevron,outline,22),lp(dp(c,22),dp(c,22),start=dp(c,8)))
        contentDescription=listOfNotNull(title,subtitle).joinToString(". ")
    }
    /** Row with a switch; the whole row toggles. */
    fun switchRow(c: Context, title: String, subtitle: String?, checked: Boolean, onChange: (Switch, Boolean) -> Unit): LinearLayout {
        val sw=Switch(c).apply {
            isChecked=checked; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable=false; isFocusable=false
            val states=arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf())
            thumbTintList=ColorStateList(states,intArrayOf(surface,surface))
            trackTintList=ColorStateList(states,intArrayOf(accent,outline))
        }
        val row=listRow(c,title,subtitle,trailing=sw)
        row.isClickable=true; row.isFocusable=true; row.background=RippleDrawable(ripple(),null,ColorDrawable(Color.BLACK))
        row.accessibilityDelegate=object: View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.className=Switch::class.java.name; info.isCheckable=true; info.isChecked=sw.isChecked
            }
        }
        row.setOnClickListener { sw.isChecked=!sw.isChecked; onChange(sw,sw.isChecked) }
        return row
    }
    /** Inline message card: tone border/fill, icon, optional title and one text action. */
    fun banner(c: Context, tone: Tone, title: String?, body: String, action: String? = null, onAction: (() -> Unit)? = null) = LinearLayout(c).apply {
        orientation=LinearLayout.HORIZONTAL
        background=rounded(if(tone==Tone.NEUTRAL || tone==Tone.INFO) surface else bg(tone),dp(c,12).toFloat(),border(tone),maxOf(1,dp(c,1)))
        setPadding(dp(c,14),dp(c,14),dp(c,14),dp(c,if(action!=null) 6 else 14))
        val res=when(tone) { Tone.ERROR -> R.drawable.ic_warning; Tone.YOU -> R.drawable.ic_person; Tone.OK -> R.drawable.ic_check_circle; else -> R.drawable.ic_info }
        addView(icon(c,res,fg(tone),22),lp(dp(c,22),dp(c,22),top=dp(c,2)))
        val col=vertical(c)
        if(title!=null) col.addView(text(c,title,Type.BODY,if(tone==Tone.NEUTRAL || tone==Tone.INFO) ink else fg(tone),bold=true).apply { setPadding(0,0,0,dp(c,2)) })
        col.addView(text(c,body,Type.LABEL+1,ink).apply { setPadding(0,0,0,0) })
        if(action!=null && onAction!=null) col.addView(tertiary(c,action,accentDark,onAction),lp(-2,top=dp(c,4)).apply { marginStart=-dp(c,12) })
        addView(col,lp(0,weight=1f,start=dp(c,12)))
        accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    fun pill(c: Context, value: String, fg: Int, bg: Int) = TextView(c).apply {
        text=value; textSize=size(Type.CAPTION); typeface=semibold; setTextColor(fg)
        background=rounded(bg,dp(c,999).toFloat()); setPadding(dp(c,10),dp(c,4),dp(c,10),dp(c,4))
    }
    /** Status word in its tone colour, no fill (e.g. "Verified", "You did it"). */
    fun badge(c: Context, value: String, tone: Tone) = TextView(c).apply {
        text=value; textSize=size(Type.CAPTION); typeface=semibold; setTextColor(fg(tone)); setPadding(0,0,0,0)
    }

    // ---------- Inputs ----------
    fun field(c: Context, hintText: String) = EditText(c).apply {
        hint=hintText; textSize=size(Type.BODY_LARGE); setTextColor(ink); setHintTextColor(muted); setSingleLine(false); typeface=regular
        minHeight=dp(c,56); setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12))
        background=fieldBackground(c)
    }
    private fun fieldBackground(c: Context): Drawable {
        val normal=rounded(surface,dp(c,12).toFloat(),outline,maxOf(1,(1.5f*c.resources.displayMetrics.density).toInt()))
        val focused=rounded(surface,dp(c,12).toFloat(),accent,dp(c,2))
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),focused); addState(intArrayOf(),normal)
        }
    }
    /** Labelled text field with optional helper line. Label is the accessible name. */
    class Field(val view: LinearLayout, val edit: EditText)
    fun labeledField(c: Context, label: String, hintText: String = "", helper: String? = null): Field {
        val box=vertical(c)
        val name=text(c,label,Type.LABEL,ink,bold=true).apply { setPadding(dp(c,2),0,0,dp(c,6)) }
        val edit=field(c,hintText).apply { contentDescription=null; labelFor=View.NO_ID }
        name.labelFor=View.generateViewId().also { edit.id=it }
        box.addView(name); box.addView(edit)
        if(helper!=null) box.addView(text(c,helper,Type.CAPTION,muted).apply { setPadding(dp(c,2),dp(c,6),0,0) })
        return Field(box,edit)
    }
    /** Segmented control: one choice of several, each an outlined box; the selected one is navy-tinted. */
    fun segmented(c: Context, options: List<String>, selected: Int, spoken: List<String> = options, sizes: List<Float>? = null, onSelect: (Int) -> Unit) = LinearLayout(c).apply {
        options.forEachIndexed { i, o ->
            val on=i==selected
            addView(baseButton(c,o,56) { onSelect(i) }.apply {
                textSize=(sizes?.get(i) ?: Type.BODY).let { if(sizes!=null) it else size(it) }
                setTextColor(if(on) accentDark else ink)
                background=pressable(c,if(on) accentSoft else surface,if(on) accent else outline)
                contentDescription=spoken[i]; isSelected=on; setPadding(dp(c,6),0,dp(c,6),0)
                stateDescription=c.getString(if(on) R.string.state_selected else R.string.state_not_selected)
            },lp(0,weight=1f,start=if(i>0) dp(c,8) else 0))
        }
    }
    /** Radio-style row for a single choice in a [group]. */
    fun radioRow(c: Context, title: String, selected: Boolean, click: () -> Unit) = row(c,click).apply {
        val ring=GradientDrawable().apply { shape=GradientDrawable.OVAL; setStroke(dp(c,2),if(selected) accent else outline); setColor(Color.TRANSPARENT) }
        val dot=GradientDrawable().apply { shape=GradientDrawable.OVAL; setColor(if(selected) accent else Color.TRANSPARENT) }
        val mark=View(c).apply { background=LayerDrawable(arrayOf(ring,InsetDrawable(dot,dp(c,6)))); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        addView(mark,lp(dp(c,24),dp(c,24),end=dp(c,14)))
        addView(text(c,title,Type.BODY,bold=selected).apply { setPadding(0,0,0,0) },lp(0,weight=1f))
        isSelected=selected
        accessibilityDelegate=object: View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.className=RadioButton::class.java.name; info.isCheckable=true; info.isChecked=selected
            }
        }
        contentDescription=title
    }

    // ---------- Navigation ----------
    /** Brand mark: navy rounded square with a chat glyph. */
    fun logo(c: Context) = FrameLayout(c).apply {
        background=rounded(accent,dp(c,8).toFloat()); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon(c,R.drawable.ic_chat,onAccent,18),FrameLayout.LayoutParams(dp(c,18),dp(c,18),Gravity.CENTER))
    }
    /** White 64dp top bar: "‹ Back" (in words) or nothing, title, trailing actions. Title is a heading. */
    fun topBar(a: Activity, title: String?, onBack: (() -> Unit)?, vararg actions: View) = LinearLayout(a).apply {
        gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(a,64); setPadding(dp(a,if(onBack!=null) 4 else 16),0,dp(a,8),0); setBackgroundColor(surface)
        if(onBack!=null) addView(tertiary(a,a.getString(R.string.back),accentDark) { onBack() }.apply {
            val chev=a.getDrawable(R.drawable.ic_back)?.mutate()?.apply { setTint(accentDark); setBounds(0,0,dp(a,20),dp(a,20)) }
            setCompoundDrawablesRelative(chev,null,null,null); compoundDrawablePadding=dp(a,4)
        },lp(-2))
        if(title!=null) addView(text(a,title,Type.BODY_LARGE,bold=true).apply { isAccessibilityHeading=true; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(0,0,0,0) },
            lp(0,weight=1f,start=dp(a,if(onBack!=null) 4 else 0)))
        else addView(View(a),lp(0,weight=1f))
        actions.forEach { addView(it) }
    }

    // ---------- Steps ----------
    enum class Mark { PENDING, CURRENT, DONE, FAILED, OFF }
    /** One step of a vertical list: status mark on the left, [content] to the right. */
    fun timelineStep(c: Context, number: Int, mark: Mark, last: Boolean, content: View) = LinearLayout(c).apply {
        val markView: View = when(mark) {
            Mark.DONE -> icon(c,R.drawable.ic_check_circle,ok,24)
            Mark.FAILED -> icon(c,R.drawable.ic_warning,stop,24)
            else -> TextView(c).apply {
                text="$number"; textSize=14f; gravity=Gravity.CENTER; typeface=bold
                val (fill,fgc,stroke)=when(mark) {
                    Mark.CURRENT -> Triple(accent,onAccent,accent)
                    Mark.OFF -> Triple(bubble,muted,bubble)
                    else -> Triple(accentSoft,accentDark,accentSoft)
                }
                setTextColor(fgc); background=rounded(fill,dp(c,14).toFloat(),stroke,dp(c,1))
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        addView(markView,LinearLayout.LayoutParams(dp(c,28),dp(c,28)).apply { topMargin=dp(c,1) })
        content.setPadding(content.paddingLeft,content.paddingTop,content.paddingRight,dp(c,if(last) 0 else 14))
        addView(content,lp(0,weight=1f,start=dp(c,12)))
    }
    /** "You do this part" line: person icon + text in the YOU tone. */
    fun youLine(c: Context, value: String) = horizontal(c).apply {
        addView(icon(c,R.drawable.ic_person,amber,18))
        addView(text(c,value,Type.LABEL,amber,bold=true).apply { setPadding(0,0,0,0) },lp(0,weight=1f,start=dp(c,6)))
    }

    // ---------- Sheets ----------
    /** Bottom sheet dialog in the app's style. [build] fills the sheet; call the passed dismiss to close. */
    fun sheet(a: Activity, build: (LinearLayout, () -> Unit) -> Unit): Dialog {
        val d=Dialog(a)
        val content=vertical(a).apply {
            background=corners(a,surface,16,16,0,0)
            setPadding(dp(a,20),dp(a,24),dp(a,20),dp(a,16))
        }
        build(content) { d.dismiss() }
        d.setContentView(content)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM); setDimAmount(0.6f)
            setDecorFitsSystemWindows(false)
            content.setOnApplyWindowInsetsListener { v, insets ->
                val bars=insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime())
                v.setPadding(dp(a,20),dp(a,24),dp(a,20),dp(a,16)+bars.bottom); insets }
        }
        d.show()
        return d
    }
    /** Confirmation sheet: title, message, primary (or destructive) confirm and a cancel. */
    fun confirm(a: Activity, title: String, message: String, confirmLabel: String, cancelLabel: String,
                destructive: Boolean = false, onCancel: () -> Unit = {}, onConfirm: () -> Unit) {
        var confirmed=false
        val d=sheet(a) { s, close ->
            s.addView(title(a,title,22f))
            s.addView(text(a,message,Type.BODY,muted))
            s.addView((if(destructive) destructive(a,confirmLabel) { confirmed=true; close(); onConfirm() }
                       else primary(a,confirmLabel) { confirmed=true; close(); onConfirm() }),lp(top=dp(a,20)))
            s.addView(secondary(a,cancelLabel) { close() },lp(top=dp(a,10)))
        }
        d.setOnDismissListener { if(!confirmed) onCancel() }
    }
}
