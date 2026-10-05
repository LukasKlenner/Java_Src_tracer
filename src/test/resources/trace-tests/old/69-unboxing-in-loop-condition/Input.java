class Input {
    public static void main(String[] args) {
        Integer count = Integer.valueOf(2);
        int sum = 0;
        while (count > 0) {
            sum += count;
            count = Integer.valueOf(count - 1);
        }
    }
}
