
package master.flame.danmaku.danmaku.model;

public class Duration implements Cloneable {

    private long mInitialDuration;

    private float factor = 1.0f;

    public long value;

    public Duration(long initialDuration) {
        mInitialDuration = initialDuration;
        value = initialDuration;
    }
    
    public void setValue(long initialDuration) {
        mInitialDuration = initialDuration;
        value = (long) (mInitialDuration * factor);
    }

    /**
     * CineIsle v115 修改（原库 Bug 修复）：
     * 原实现带 `if (factor != f)` 守卫 —— 当外部先把 factor 改过、再 setValue 时
     * 不会重算 value，导致「已调速」状态下新写入的 mInitialDuration 不生效。
     * setValue/setFactor 都是低频调用，无条件重算没有性能问题。
     */
    public void setFactor(float f) {
        factor = f;
        value = (long) (mInitialDuration * f);
    }

    /**
     * CineIsle v115 新增（核心修复）：**存活寿命**，只认 mInitialDuration，
     * **不受滚动速度系数（factor）影响**。
     *
     * 原库把「速度」与「寿命」压在同一个 value 上：
     *   - 速度：R2LDanmaku.measure() 的 mStepX = (屏宽+字宽)/duration.value
     *   - 寿命：BaseDanmaku.isTimeOut() 的 ctime - time >= duration.value
     * setScrollSpeedFactor(f) 就是 Duration.setFactor(f) ⇒ value = mInitialDuration × f
     * ⇒ f 越大弹幕越慢**且寿命越长**，两个概念被焊死在一起，用户无法只要「慢」不要「长」。
     * 现在：速度读 value（吃 factor），寿命读 getLifetime()（不吃 factor），两者解耦。
     */
    public long getLifetime() {
        return mInitialDuration;
    }

}
