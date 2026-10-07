class Input {

    public int getNumber(int i) {

        if (i == 1) {
            return 1;
        }

        if (i == 2) {
            return 2;
        }

        if (i == 3) {
            return 3;
        }

        if (i == 4) {
            return 4;
        }

        return 0;
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int[][] arr = new int[][]{{obj.getNumber(1), obj.getNumber(2)}, {obj.getNumber(3), obj.getNumber(4)}};
    }
}
