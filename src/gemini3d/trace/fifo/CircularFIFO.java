package gemini3d.trace.fifo;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


public class CircularFIFO<T> {

    private final T[] buffer;
    private final int capacity;

    private int writePos;
    private int readPos;
    private int count;

    private volatile boolean closed = false;

    // Thread safety
    private final ReentrantLock lock;
    private final Condition notEmpty;

    // Stats
    private final AtomicInteger totalWritten  = new AtomicInteger(0);
    private final AtomicInteger totalRead     = new AtomicInteger(0);
    private final AtomicInteger totalDropped  = new AtomicInteger(0);
    private volatile int peakSize = 0;

    /**
     * Creates a circular FIFO with the given capacity.
     *
     * @param capacity the maximum number of elements before overwriting starts
     */
    @SuppressWarnings("unchecked")
    public CircularFIFO(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be >= 1");
        this.capacity = capacity;
        this.buffer = (T[]) new Object[capacity];
        this.writePos = 0;
        this.readPos = 0;
        this.count = 0;
        this.lock = new ReentrantLock();
        this.notEmpty = lock.newCondition();
    }


    /**
     * Writes an item into the FIFO. NEVER blocks.
     * If the buffer is full, the OLDEST item is overwritten (dropped).
     *
     * @param item the item to write (must not be null)
     */
    public void write(T item) {
        lock.lock();
        try {
            if (count == capacity) {
                // Buffer full -- overwrite oldest, advance readPos
                readPos = (readPos + 1) % capacity;
                totalDropped.incrementAndGet();
            } else {
                count++;
            }

            buffer[writePos] = item;
            writePos = (writePos + 1) % capacity;

            // Track peak
            if (count > peakSize) peakSize = count;

            totalWritten.incrementAndGet();

            // Signal any blocked reader
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the FIFO. No more data will be written.
     */
    public void close() {
        lock.lock();
        try {
            closed = true;
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }


    /**
     * @return the oldest item, or null if closed and empty
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public T read() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) {
                if (closed) return null;  // End of stream
                notEmpty.await();
            }

            T item = buffer[readPos];
            buffer[readPos] = null;  // Help GC
            readPos = (readPos + 1) % capacity;
            count--;

            totalRead.incrementAndGet();
            return item;
        } finally {
            lock.unlock();
        }
    }

    // --- Monitoring ----------------------------------------------------------

    /** Current number of items in the buffer. */
    public int size() {
        lock.lock();
        try { return count; }
        finally { lock.unlock(); }
    }

    /** Maximum capacity of the ring buffer. */
    public int getCapacity()     { return capacity; }

    /** Total items written since creation. */
    public int getTotalWritten() { return totalWritten.get(); }

    /** Total items successfully read since creation. */
    public int getTotalRead()    { return totalRead.get(); }

    /** Total items dropped (overwritten) since creation. */
    public int getTotalDropped() { return totalDropped.get(); }

    /** Peak buffer occupancy observed. */
    public int getPeakSize()     { return peakSize; }

    /** Whether the FIFO has been closed. */
    public boolean isClosed()    { return closed; }
}