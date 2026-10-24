class Input {
    public static void main(String[] args) {
        Object[] arr = new String[3];
        try {
            arr[1] = new Object();
        } catch (ArrayStoreException e) {
        }
    }
}
