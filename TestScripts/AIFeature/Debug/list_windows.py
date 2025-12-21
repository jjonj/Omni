import pygetwindow as gw

def list_titles():
    titles = gw.getAllTitles()
    print("--- Current Window Titles ---")
    for t in titles:
        if t.strip():
            print(f"'{t}'")
    print("--- End Titles ---")

if __name__ == "__main__":
    list_titles()
