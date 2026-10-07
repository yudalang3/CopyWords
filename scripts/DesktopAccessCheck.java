import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;

// Read-only diagnosis when Robot/SendInput tests cannot reach the interactive desktop.
public class DesktopAccessCheck {
    public interface DesktopApi extends StdCallLibrary {
        Pointer GetThreadDesktop(int thread);
        Pointer GetProcessWindowStation();
        Pointer OpenInputDesktop(int flags, boolean inherit, int access);
        boolean CloseDesktop(Pointer desktop);
        boolean GetUserObjectInformationW(Pointer object, int index, Pointer data, int length, Pointer needed);
    }
    static String name(DesktopApi api, Pointer object) {
        if (object == null) return "unavailable (error=" + Kernel32.INSTANCE.GetLastError() + ")";
        var data = new Memory(512); var needed = new Memory(4);
        return api.GetUserObjectInformationW(object, 2, data, 512, needed)
                ? data.getWideString(0) : "unknown (error=" + Kernel32.INSTANCE.GetLastError() + ")";
    }
    public static void main(String[] args) {
        var api = Native.load("user32", DesktopApi.class);
        System.out.println("Window station: " + name(api, api.GetProcessWindowStation()));
        System.out.println("Thread desktop: " + name(api, api.GetThreadDesktop(Kernel32.INSTANCE.GetCurrentThreadId())));
        Pointer input = api.OpenInputDesktop(0, false, 1);
        System.out.println("Input desktop: " + name(api, input));
        if (input != null) api.CloseDesktop(input);
        System.out.println("Foreground window: " + User32.INSTANCE.GetForegroundWindow());
    }
}
