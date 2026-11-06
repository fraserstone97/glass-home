package app.glasshome.launcher;

/**
 * Lets TrailerWatch follow the YouTube player: Android shares media sessions only with an app
 * allowed to listen to notifications. It reads no notifications. install.sh allows it (cmd
 * notification allow_listener); without that, trailers play in YouTube's bare player instead.
 */
public class MediaListener extends android.service.notification.NotificationListenerService {
}
