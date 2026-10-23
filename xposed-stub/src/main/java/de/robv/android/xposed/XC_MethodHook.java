package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Xposed API stub（编译占位），运行时由 LSPosed 注入真实实现。
 */
public abstract class XC_MethodHook {

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    @SuppressWarnings("unchecked")
    public static class MethodHookParam<T> {

        public T thisObject;
        public Member method;
        public Object[] args;

        private Object result = null;
        private Throwable throwable = null;
        boolean returnEarly = false;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }
    }

    public static class Unhook {

        public void unhook() {
        }

        public Member getHookedMethod() {
            return null;
        }
    }
}
