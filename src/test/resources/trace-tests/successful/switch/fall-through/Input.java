class Input {

    void m(int i) {
        if (i == 2) {
            int x = 1;
        }
    }

    public static void main(String[] args) {
        Input obj = new Input();
        int a = 2;

        switch (a) {
            case 1:
                break;
            case 2: {
                obj.m(2);
            }
            case 3: {
                obj.m(3);
                break;
            }
            default:
                int z = 3;
                break;
        }
    }
}
