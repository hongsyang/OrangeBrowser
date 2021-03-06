package com.dev.base.mvvm;
/**
 * Event只能消费一次
 * */
public class SingleConsumeEvent<T> {

    private T mContent;

    private boolean hasBeenHandled = false;

    public SingleConsumeEvent(T content) {
        if (content == null) {
            throw new IllegalArgumentException("null values in SingleConsumeEvent are not allowed.");
        }
        mContent = content;
    }

    public T getContentIfNotHandled() {
        if (hasBeenHandled) {
            return null;
        } else {
            hasBeenHandled = true;
            return mContent;
        }
    }

    public boolean hasBeenHandled() {
        return hasBeenHandled;
    }
}
