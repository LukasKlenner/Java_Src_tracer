class Input {

    void m() {

    }

    public static void main(String[] args) {
        Input obj = new Input();
        int a = 1;

        switch (a) {
            case 1: {
                obj.m();
                break;
            }
            case 2: {
                int y = 2;
                break;
            }
            default: {
                int z = 3;
                break;
            }
        }
    }
}
