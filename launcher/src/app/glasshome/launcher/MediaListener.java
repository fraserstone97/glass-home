package app.glasshome.launcher;

/**
 * Lets Control Centre show what's playing: Android shares the media sessions (title, artwork,
 * play and pause) only with an app allowed to listen to notifications. It reads none of them.
 * install.sh allows it (cmd notification allow_listener); without that, Control Centre just
 * has no Now Playing.
 */
public class MediaListener extends android.service.notification.NotificationListenerService {
}
