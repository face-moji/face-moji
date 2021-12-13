import os
import numpy
from sys import argv

def sum_and_sort_line(platform_lines: list, metric):
    cnt_sum = [0] * 95
    sorted_index = []
    for platform_line in platform_lines:
        for i, cnt in enumerate(platform_line):
            cnt_sum[i] = cnt_sum[i] + float(cnt)

    for i in range(95):
        if(metric == "rmse"):
            ind = cnt_sum.index(min(cnt_sum))
            sorted_index.append(ind)
            cnt_sum[ind] = max(cnt_sum)
        else:
            ind = cnt_sum.index(max(cnt_sum))
            sorted_index.append(ind)
            cnt_sum[ind] = min(cnt_sum)

    return sorted_index


def summ(metric):
    platform_results = []
    for platform in ["Apple", "Facebook", "Google", "Samsung", "Twitter"]:
        platform_results.append(open("results/{}/{}.txt".format(platform, metric)))

    done = False
    sorted_index_lines = []

    linenumber = 0

    while True: # looping through lines
        platform_lines = []
        for i in range(5):
            pr = platform_results[i]
            line = pr.readline()
            if not line:
                done = True
                break
            listline = list(line.split(' '))[:-1]
            platform_lines.append(listline)
        if done: break
        sorted_index_lines.append(sum_and_sort_line(platform_lines, metric)[1:])
        linenumber = linenumber + 1

    return sorted_index_lines # list of list of indices


def write_file(lines, output_dir):
    output = open(output_dir, 'w')
    for line in lines:
        for ind in line:
            output.write("{} ".format(ind))
        output.write("\n")
    output.close()

def main():
    if len(argv) < 2 :
        usage_msg = """
        USAGE:
               python3 sum_platforms.py {output directory}
        """
        print(usage_msg)
        quit()

    output_dir = argv[1]
    if not os.path.isdir(output_dir):
        print("invalid directory")
        quit()

    for metric in ["psnr", "rmse", "sre", "ssim"]: # removed sam...
        print("# Summing results for {}".format(metric))
        lines = summ(metric)
        write_file(lines, "{}/{}.txt".format(output_dir, metric))

if __name__ == "__main__":
    main()
