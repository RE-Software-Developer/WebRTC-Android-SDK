package org.webrtc;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

public class AnimationUtil {
    private static ValueAnimator colorAnimation;

    public static void flashAnimation(final View view, int colorFrom, int colorTo) {
        if (colorAnimation == null) {
            final GradientDrawable drawable = (GradientDrawable) view.getBackground();

            colorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), colorFrom, colorTo);
            colorAnimation.setDuration(250); // milliseconds
            colorAnimation.setRepeatCount(5);
            colorAnimation.setRepeatMode(ValueAnimator.REVERSE);
            colorAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animator) {
                    drawable.setStroke(5, (Integer) animator.getAnimatedValue());
                }
            });
            colorAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    view.setVisibility(View.GONE);
                }
            });
        }

        colorAnimation.cancel();
        view.setVisibility(View.VISIBLE);
        colorAnimation.start();
    }

    public static void release() {
        if (colorAnimation != null) {
            colorAnimation.cancel();
            colorAnimation = null;
        }
    }
}
