package choco.ratel.smartmoving.windrunner;

/** Per-player transient flight and Windrunner state; supplied by the native Player mixin. */
public interface WindrunnerUser {
    WindrunnerGlide windrunner$glide();
    WindrunnerMoves windrunner$moves();
}
