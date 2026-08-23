class Input {
    public static void main(String[] args) {
        Object lock = new Object();
        synchronized (lock) {
            int x = 1;
        }
    }
}
