package stormpot.qpool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import stormpot.Allocator;
import stormpot.Config;
import stormpot.Poolable;

@SuppressWarnings("unchecked")
class QAllocThread<T extends Poolable> extends Thread {
  private final CountDownLatch completionLatch;
  private final BlockingQueue<QSlot<T>> live;
  private final BlockingQueue<QSlot<T>> dead;
  private final Allocator<T> allocator;
  private final int targetSize;
  private final long ttlMillis;
  private int size;

  public QAllocThread(
      BlockingQueue<QSlot<T>> live, BlockingQueue<QSlot<T>> dead,
      Config<T> config) {
    this.targetSize = config.getSize();
    if (targetSize < 1) {
      throw new IllegalArgumentException("size must be at least 1");
    }
    completionLatch = new CountDownLatch(1);
    this.allocator = config.getAllocator();
    this.size = 0;
    this.live = live;
    this.dead = dead;
    ttlMillis = config.getTTLUnit().toMillis(config.getTTL());
  }

  @Override
  public void run() {
    try {
      for (;;) {
        if (size < targetSize) {
          QSlot slot = new QSlot(live);
          alloc(slot);
        }
        QSlot slot = dead.poll(50, TimeUnit.MILLISECONDS);
        if (slot != null) {
          dealloc(slot);
          alloc(slot);
        }
      }
    } catch (InterruptedException e) {
      // we're shut down
      while (size > 0) {
        QSlot<T> slot = dead.poll();
        if (slot == null) {
          slot = live.poll();
        }
        if (slot == QueuePool.KILL_PILL) {
          live.offer(QueuePool.KILL_PILL);
          slot = null;
        }
        if (slot == null) {
          LockSupport.parkNanos(10000000); // 10 millis
        } else {
          dealloc(slot);
        }
      }
    } finally {
      completionLatch.countDown();
    }
  }

  private void alloc(QSlot slot) {
    try {
      slot.obj = allocator.allocate(slot);
      if (slot.obj == null) {
        slot.poison = new NullPointerException("allocation returned null");
      }
    } catch (Exception e) {
      slot.poison = e;
    }
    size++;
    slot.expires = System.currentTimeMillis() + ttlMillis;
    slot.claim();
    slot.release();
  }

  private void dealloc(QSlot<T> slot) {
    size--;
    try {
      if (slot.poison == null) {
        allocator.deallocate(slot.obj);
      }
    } catch (Exception _) {
      // ignored as per specification
    } finally {
      slot.poison = null;
      slot.obj = null;
    }
  }

  public void await() throws InterruptedException {
    completionLatch.await();
  }

  public boolean await(long timeout, TimeUnit unit)
      throws InterruptedException {
    return completionLatch.await(timeout, unit);
  }
}