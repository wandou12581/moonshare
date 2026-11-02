using System.Windows;

namespace MoonView;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += (_, args) =>
        {
            AppLog.Error("Unhandled UI exception", args.Exception);
            MessageBox.Show(
                args.Exception.Message,
                "MoonView",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
            args.Handled = true;
        };
    }
}
