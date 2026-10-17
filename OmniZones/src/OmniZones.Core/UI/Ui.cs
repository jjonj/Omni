using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace OmniZones.UI;

/// <summary>Small code-only UI toolkit so the windows need no XAML or app-level resources (safe to host in any process).</summary>
internal static class Ui
{
    public static readonly Brush Bg = Brush("#14161B");
    public static readonly Brush Card = Brush("#1E2129");
    public static readonly Brush Ink = Brush("#E8EAF0");
    public static readonly Brush Muted = Brush("#98A0B0");
    public static readonly Brush Line = Brush("#3A3F4B");
    public static readonly Brush Accent = Brush("#F5A524");
    public static readonly Brush Tape = Brush("#F5A524");
    public static readonly Brush Selected = Brush("#4A3A18");

    private const string ThemeXaml = """
<ResourceDictionary xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
 <Style TargetType="Button">
  <Setter Property="Template"><Setter.Value><ControlTemplate TargetType="Button">
   <Grid>
    <Border x:Name="bd" Background="{TemplateBinding Background}" BorderBrush="{TemplateBinding BorderBrush}" BorderThickness="1" CornerRadius="5"/>
    <Border x:Name="hv" Background="White" Opacity="0" CornerRadius="5"/>
    <ContentPresenter Margin="{TemplateBinding Padding}" HorizontalAlignment="{TemplateBinding HorizontalContentAlignment}" VerticalAlignment="Center"/>
   </Grid>
   <ControlTemplate.Triggers>
    <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="hv" Property="Opacity" Value="0.12"/></Trigger>
    <Trigger Property="IsPressed" Value="True"><Setter TargetName="hv" Property="Opacity" Value="0.22"/></Trigger>
    <Trigger Property="IsEnabled" Value="False"><Setter Property="Opacity" Value="0.5"/></Trigger>
   </ControlTemplate.Triggers>
  </ControlTemplate></Setter.Value></Setter>
 </Style>
 <Style TargetType="CheckBox">
  <Setter Property="Template"><Setter.Value><ControlTemplate TargetType="CheckBox">
   <Grid Background="Transparent">
    <Grid.ColumnDefinitions><ColumnDefinition Width="Auto"/><ColumnDefinition Width="*"/></Grid.ColumnDefinitions>
    <Border x:Name="bx" Width="17" Height="17" CornerRadius="4" BorderThickness="1" BorderBrush="#5A6272" Background="#1E2129" VerticalAlignment="Top" Margin="0,1,9,0">
     <Path x:Name="ck" Data="M3.5,8.5 L7,12 L13,4.5" Stroke="#1B1305" StrokeThickness="2" Visibility="Collapsed"/>
    </Border>
    <ContentPresenter Grid.Column="1" VerticalAlignment="Center"/>
   </Grid>
   <ControlTemplate.Triggers>
    <Trigger Property="IsChecked" Value="True"><Setter TargetName="ck" Property="Visibility" Value="Visible"/><Setter TargetName="bx" Property="Background" Value="#F5A524"/><Setter TargetName="bx" Property="BorderBrush" Value="#F5A524"/></Trigger>
    <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="bx" Property="BorderBrush" Value="#98A0B0"/></Trigger>
    <Trigger Property="IsEnabled" Value="False"><Setter Property="Opacity" Value="0.5"/></Trigger>
   </ControlTemplate.Triggers>
  </ControlTemplate></Setter.Value></Setter>
 </Style>
 <Style TargetType="TextBox">
  <Setter Property="Background" Value="#1E2129"/><Setter Property="Foreground" Value="#E8EAF0"/><Setter Property="BorderBrush" Value="#3A3F4B"/>
  <Setter Property="CaretBrush" Value="#E8EAF0"/><Setter Property="SelectionBrush" Value="#F5A524"/>
  <Setter Property="Template"><Setter.Value><ControlTemplate TargetType="TextBox">
   <Border x:Name="bd" Background="{TemplateBinding Background}" BorderBrush="{TemplateBinding BorderBrush}" BorderThickness="1" CornerRadius="4">
    <ScrollViewer x:Name="PART_ContentHost" Padding="{TemplateBinding Padding}" VerticalAlignment="Center"/>
   </Border>
   <ControlTemplate.Triggers><Trigger Property="IsKeyboardFocused" Value="True"><Setter TargetName="bd" Property="BorderBrush" Value="#F5A524"/></Trigger></ControlTemplate.Triggers>
  </ControlTemplate></Setter.Value></Setter>
 </Style>
 <Style TargetType="ComboBoxItem">
  <Setter Property="Foreground" Value="#E8EAF0"/>
  <Setter Property="Template"><Setter.Value><ControlTemplate TargetType="ComboBoxItem">
   <Border x:Name="b" Padding="9,5" Background="Transparent"><ContentPresenter/></Border>
   <ControlTemplate.Triggers>
    <Trigger Property="IsHighlighted" Value="True"><Setter TargetName="b" Property="Background" Value="#3A3F4B"/></Trigger>
    <Trigger Property="IsSelected" Value="True"><Setter TargetName="b" Property="Background" Value="#4A3A18"/></Trigger>
   </ControlTemplate.Triggers>
  </ControlTemplate></Setter.Value></Setter>
 </Style>
 <Style TargetType="ComboBox">
  <Setter Property="Foreground" Value="#E8EAF0"/>
  <Setter Property="Template"><Setter.Value><ControlTemplate TargetType="ComboBox">
   <Grid>
    <ToggleButton x:Name="tb" Focusable="False" ClickMode="Press" IsChecked="{Binding IsDropDownOpen, Mode=TwoWay, RelativeSource={RelativeSource TemplatedParent}}">
     <ToggleButton.Template><ControlTemplate TargetType="ToggleButton">
      <Border x:Name="bd" Background="#1E2129" BorderBrush="#3A3F4B" BorderThickness="1" CornerRadius="4">
       <Path Data="M0,0 L4,4 L8,0" Stroke="#98A0B0" StrokeThickness="1.6" HorizontalAlignment="Right" VerticalAlignment="Center" Margin="0,0,10,0"/>
      </Border>
      <ControlTemplate.Triggers><Trigger Property="IsMouseOver" Value="True"><Setter TargetName="bd" Property="BorderBrush" Value="#98A0B0"/></Trigger></ControlTemplate.Triggers>
     </ControlTemplate></ToggleButton.Template>
    </ToggleButton>
    <ContentPresenter IsHitTestVisible="False" Content="{TemplateBinding SelectionBoxItem}" Margin="9,2,28,2" VerticalAlignment="Center" HorizontalAlignment="Left"/>
    <Popup IsOpen="{TemplateBinding IsDropDownOpen}" Placement="Bottom" AllowsTransparency="True" Focusable="False">
     <Border MinWidth="{Binding ActualWidth, ElementName=tb}" MaxHeight="{TemplateBinding MaxDropDownHeight}" Background="#1E2129" BorderBrush="#3A3F4B" BorderThickness="1" CornerRadius="4" Margin="0,2,6,6">
      <ScrollViewer><ItemsPresenter KeyboardNavigation.DirectionalNavigation="Contained"/></ScrollViewer>
     </Border>
    </Popup>
   </Grid>
  </ControlTemplate></Setter.Value></Setter>
 </Style>
 <Style TargetType="ScrollBar">
  <Setter Property="Width" Value="12"/>
  <Setter Property="Template"><Setter.Value><ControlTemplate TargetType="ScrollBar">
   <Grid Background="Transparent">
    <Track x:Name="PART_Track" IsDirectionReversed="True">
     <Track.DecreaseRepeatButton><RepeatButton Command="ScrollBar.PageUpCommand" Opacity="0"/></Track.DecreaseRepeatButton>
     <Track.IncreaseRepeatButton><RepeatButton Command="ScrollBar.PageDownCommand" Opacity="0"/></Track.IncreaseRepeatButton>
     <Track.Thumb><Thumb><Thumb.Template><ControlTemplate TargetType="Thumb"><Border Margin="2" CornerRadius="4" Background="#4A505E"/></ControlTemplate></Thumb.Template></Thumb></Track.Thumb>
    </Track>
   </Grid>
  </ControlTemplate></Setter.Value></Setter>
 </Style>
</ResourceDictionary>
""";

    /// <summary>Dark control templates and a dark title bar for a window. Code-only, so it works in any host process.</summary>
    public static void Theme(Window w)
    {
        w.Background = Bg;
        w.Foreground = Ink;
        try { w.Resources.MergedDictionaries.Add((ResourceDictionary)System.Windows.Markup.XamlReader.Parse(ThemeXaml)); } catch { /* fall back to the system look */ }
        w.SourceInitialized += (_, _) =>
        {
            try
            {
                var h = new System.Windows.Interop.WindowInteropHelper(w).Handle;
                var on = 1;
                OmniZones.Native.Win32.DwmSetWindowAttribute(h, OmniZones.Native.Win32.DWMWA_USE_IMMERSIVE_DARK_MODE, ref on, sizeof(int));
            }
            catch { /* older Windows: light title bar */ }
        };
    }

    public static SolidColorBrush Brush(string hex)
    {
        var b = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));
        b.Freeze();
        return b;
    }

    public static TextBlock Text(string text, double size = 13, bool bold = false, bool muted = false) => new()
    {
        Text = text, FontSize = size, FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal,
        Foreground = muted ? Muted : Ink, TextWrapping = TextWrapping.Wrap,
    };

    public static TextBlock Heading(string text) => new()
    {
        Text = text.ToUpperInvariant(), FontSize = 11, FontWeight = FontWeights.SemiBold, Foreground = Accent,
        Margin = new Thickness(0, 14, 0, 4),
    };

    public static TextBlock Hint(string text) => new()
    {
        Text = text, FontSize = 12, Foreground = Muted, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(22, 0, 0, 6),
    };

    public static CheckBox Check(string text, Action<bool> changed, bool hasHintBelow = false)
    {
        var c = new CheckBox { Content = text, Margin = new Thickness(0, 6, 0, hasHintBelow ? 0 : 6), FontSize = 13, Foreground = Ink };
        c.Click += (_, _) => changed(c.IsChecked == true);
        return c;
    }

    public static Button Button(string text, Action click, bool primary = false)
    {
        var b = new Button
        {
            Content = text, Padding = new Thickness(12, 6, 12, 6), Margin = new Thickness(0, 0, 8, 0), MinHeight = 30,
            Background = primary ? Tape : Card, Foreground = primary ? Brush("#1B1305") : Ink, BorderBrush = primary ? Accent : Line,
            FontWeight = primary ? FontWeights.SemiBold : FontWeights.Normal, Cursor = Cursors.Hand,
        };
        b.Click += (_, _) => click();
        return b;
    }

    public static ComboBox Combo(IEnumerable<string> items, Action<int> changed, double width = 170)
    {
        var c = new ComboBox { Width = width, MinHeight = 28, HorizontalAlignment = HorizontalAlignment.Left };
        foreach (var i in items) c.Items.Add(i);
        c.SelectionChanged += (_, _) => { if (c.IsKeyboardFocusWithin || c.IsDropDownOpen) changed(c.SelectedIndex); };
        return c;
    }

    /// <summary>TextBox that commits on Enter or focus loss.</summary>
    public static TextBox Field(Action<string> commit, double width = double.NaN)
    {
        var t = new TextBox { MinHeight = 28, VerticalContentAlignment = VerticalAlignment.Center, Padding = new Thickness(4, 0, 4, 0) };
        if (!double.IsNaN(width)) t.Width = width;
        string last = "";
        void Commit() { if (t.Text != last) { last = t.Text; commit(t.Text); } }
        t.GotKeyboardFocus += (_, _) => last = t.Text;
        t.LostKeyboardFocus += (_, _) => Commit();
        t.KeyDown += (_, e) => { if (e.Key == Key.Enter) Commit(); };
        return t;
    }

    public static StackPanel Row(params UIElement[] children)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 3, 0, 3) };
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    public static Border CardOf(UIElement child) => new()
    {
        Background = Card, BorderBrush = Line, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(8),
        Padding = new Thickness(14, 8, 14, 12), Child = child,
    };
}
