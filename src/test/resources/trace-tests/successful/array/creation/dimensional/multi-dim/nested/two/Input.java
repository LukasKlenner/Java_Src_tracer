class Input {

    public int getSize(int i) {

        if (i == 1) {
            return 1;
        }
        return 3;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int[][] arr = new int[obj.getSize(1)][obj.getSize(2)];
    }
}
