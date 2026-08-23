class Input {
    int[] data;

    Input(int[] d) {
        this.data = d;
    }

    int getFirst() {
        return data[0];
    }

    public static void main(String[] args) {
        int[] arr = new int[]{42};
        Input obj = new Input(arr);
        int x = obj.getFirst();
    }
}
