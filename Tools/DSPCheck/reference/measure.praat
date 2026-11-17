# Measures every WAV in a folder and prints reference.json for DSPCheck --test.
#   /Applications/Praat.app/Contents/MacOS/Praat --run measure.praat "/path/to/reference" > reference.json
# F0: median of Praat's pitch track (60–600 Hz). Formants: Burg, 5 formants,
# median over the file, ceiling 5000 Hz for low voices (median F0 < 165 Hz)
# and 5500 Hz for higher ones (docs/ACCURACY.md). Files with "sentence" in
# the name get F0 only, since a formant median across many vowels means nothing.
form Reference folder
    text folder .
endform
list = Create Strings as file list: "list", folder$ + "/*.wav"
n = Get number of strings
writeInfoLine: "["
for i to n
    selectObject: list
    name$ = Get string: i
    sound = Read from file: folder$ + "/" + name$
    pitch = To Pitch: 0, 60, 600
    f0 = Get quantile: 0, 0, 0.5, "Hertz"
    selectObject: sound
    if f0 < 165
        ceiling = 5000
    else
        ceiling = 5500
    endif
    formant = To Formant (burg): 0, 5, ceiling, 0.025, 50
    f1 = Get quantile: 1, 0, 0, "hertz", 0.5
    f2 = Get quantile: 2, 0, 0, "hertz", 0.5
    f3 = Get quantile: 3, 0, 0, "hertz", 0.5
    if i < n
        sep$ = ","
    else
        sep$ = ""
    endif
    line$ = "  {""file"": """ + name$ + """, ""f0"": " + fixed$(f0, 1)
    if index(name$, "sentence") = 0
        line$ = line$ + ", ""f1"": " + fixed$(f1, 0) + ", ""f2"": " + fixed$(f2, 0) + ", ""f3"": " + fixed$(f3, 0)
    endif
    appendInfoLine: line$ + "}" + sep$
    removeObject: sound, pitch, formant
endfor
removeObject: list
appendInfoLine: "]"
