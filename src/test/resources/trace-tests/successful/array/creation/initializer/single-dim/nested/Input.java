class Input {

    public int getNumber(int i) {

        if (i == 1) {
            return 1;
        }

        return 0;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int[] arr = new int[]{obj.getNumber(1), obj.getNumber(2)};
    }
}
